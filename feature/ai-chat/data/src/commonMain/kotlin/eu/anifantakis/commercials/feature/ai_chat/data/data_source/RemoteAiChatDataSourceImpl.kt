package eu.anifantakis.commercials.feature.ai_chat.data.data_source

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.data.network.remoteCall
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.domain.util.map
import eu.anifantakis.commercials.feature.ai_chat.data.dto.AiChatRequestDto
import eu.anifantakis.commercials.feature.ai_chat.data.dto.AiChatResponseDto
import eu.anifantakis.commercials.feature.ai_chat.data.dto.AiChatTurnDto
import eu.anifantakis.commercials.feature.ai_chat.data.dto.AiExecuteRequestDto
import eu.anifantakis.commercials.feature.ai_chat.data.dto.AiExecuteResponseDto
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatMessage
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatReply
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatRole
import eu.anifantakis.commercials.feature.ai_chat.domain.AiClientAction
import eu.anifantakis.commercials.feature.ai_chat.domain.AiExecutionOutcome
import eu.anifantakis.commercials.feature.ai_chat.domain.AiProposal
import eu.anifantakis.commercials.feature.ai_chat.domain.AiToolStep
import eu.anifantakis.commercials.feature.ai_chat.domain.data_source.RemoteAiChatDataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line

class RemoteAiChatDataSourceImpl(private val api: ApiHttpClient) : RemoteAiChatDataSource {

    override suspend fun send(
        history: List<AiChatMessage>,
        provider: String,
        model: String,
        screenContext: String?,
    ): DataResult<AiChatReply, RemoteError> =
        remoteCall {
            api.client.post("/api/ai/chat") {
                // A model turn with tool calls legitimately runs way past the
                // client-wide 30s default - this call gets its own budget.
                timeout { requestTimeoutMillis = AI_REQUEST_TIMEOUT_MILLIS }
                contentType(ContentType.Application.Json)
                setBody(
                    AiChatRequestDto(
                        // NOTE turns (approved/declined action annotations) ride
                        // as user turns - the server only tells the two apart.
                        messages = history.map {
                            AiChatTurnDto(
                                role = if (it.role == AiChatRole.ASSISTANT) "assistant" else "user",
                                text = it.text,
                            )
                        },
                        provider = provider,
                        model = model,
                        screenContext = screenContext,
                    )
                )
            }.body<AiChatResponseDto>()
        }.map { it.toDomain() }

    /**
     * The NDJSON streaming variant: each `{"type":"step"}` line fires
     * [onStep] the moment the server STARTS a tool, the final
     * `{"type":"reply"}` carries the whole response, `{"type":"error"}` is
     * the in-band error envelope (the 200 header is long gone by then).
     */
    override suspend fun sendStreaming(
        history: List<AiChatMessage>,
        provider: String,
        model: String,
        screenContext: String?,
        onStep: (String) -> Unit,
    ): DataResult<AiChatReply, RemoteError> {
        var reply: AiChatReply? = null
        var streamError: String? = null
        val result = remoteCall {
        api.client.preparePost("/api/ai/chat/stream") {
            timeout { requestTimeoutMillis = AI_REQUEST_TIMEOUT_MILLIS }
            contentType(ContentType.Application.Json)
            setBody(
                AiChatRequestDto(
                    messages = history.map {
                        AiChatTurnDto(
                            role = if (it.role == AiChatRole.ASSISTANT) "assistant" else "user",
                            text = it.text,
                        )
                    },
                    provider = provider,
                    model = model,
                    screenContext = screenContext,
                )
            )
        }.execute { response ->
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (line.isBlank()) continue
                val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                    "step" -> (obj["tool"] as? JsonPrimitive)?.contentOrNull?.let(onStep)
                    "reply" -> reply = obj["reply"]?.let {
                        StreamJson.decodeFromJsonElement(AiChatResponseDto.serializer(), it).toDomain()
                    }
                    "error" -> streamError = (obj["message"] as? JsonPrimitive)?.contentOrNull ?: "AI provider error"
                }
            }
        }
        reply ?: error("stream ended without a reply")
        }
        // The in-band error envelope surfaces with the SERVER's message,
        // exactly like a non-streaming 502 would.
        streamError?.let { return DataResult.Failure(RemoteError.Server(it)) }
        return result
    }

    override suspend fun fetchReport(id: String): DataResult<ByteArray, RemoteError> =
        remoteCall { api.client.get("/api/ai/report/" + id).body<ByteArray>() }

    override suspend fun execute(
        tool: String,
        argumentsJson: String,
    ): DataResult<AiExecutionOutcome, RemoteError> =
        remoteCall {
            api.client.post("/api/ai/execute") {
                // Sending a schedule email can outlast the 30s default.
                timeout { requestTimeoutMillis = EXECUTE_TIMEOUT_MILLIS }
                contentType(ContentType.Application.Json)
                setBody(
                    AiExecuteRequestDto(
                        tool = tool,
                        arguments = Json.parseToJsonElement(argumentsJson).jsonObject,
                    )
                )
            }.body<AiExecuteResponseDto>()
        }.map { AiExecutionOutcome(it.text, it.isError) }

    /** Shared dto->domain mapping (plain and streaming paths). */
    private fun AiChatResponseDto.toDomain(): AiChatReply = AiChatReply(
        text = text,
        steps = steps.map { AiToolStep(it.tool, it.isError) },
        clientActions = clientActions.map { a ->
            AiClientAction(
                action = a.action,
                station = (a.arguments["station"] as? JsonPrimitive)?.contentOrNull,
                reportId = (a.arguments["id"] as? JsonPrimitive)?.contentOrNull,
                fileName = (a.arguments["fileName"] as? JsonPrimitive)?.contentOrNull,
            )
        },
        proposals = proposals.mapIndexed { i, p ->
            AiProposal(
                // Client-generated card key: unique across the conversation.
                id = "${p.tool}#$i@${Random.nextLong().toULong().toString(16)}",
                tool = p.tool,
                argumentsJson = p.arguments.toString(),
                preview = p.preview,
            )
        },
    )

    private companion object {
        const val AI_REQUEST_TIMEOUT_MILLIS = 180_000L
        const val EXECUTE_TIMEOUT_MILLIS = 60_000L

        /** NDJSON lines are hand-parsed - lenient to unknown fields. */
        val StreamJson = Json { ignoreUnknownKeys = true }
    }
}
