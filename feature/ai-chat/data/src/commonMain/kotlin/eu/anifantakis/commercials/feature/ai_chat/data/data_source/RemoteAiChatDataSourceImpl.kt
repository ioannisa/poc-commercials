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
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class RemoteAiChatDataSourceImpl(private val api: ApiHttpClient) : RemoteAiChatDataSource {

    override suspend fun send(
        history: List<AiChatMessage>,
        provider: String,
        model: String,
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
                    )
                )
            }.body<AiChatResponseDto>()
        }.map { dto ->
            AiChatReply(
                text = dto.text,
                steps = dto.steps.map { AiToolStep(it.tool, it.isError) },
                clientActions = dto.clientActions.map { a ->
                    AiClientAction(
                        action = a.action,
                        station = (a.arguments["station"] as? JsonPrimitive)?.contentOrNull,
                    )
                },
                proposals = dto.proposals.mapIndexed { i, p ->
                    AiProposal(
                        // Client-generated card key: unique across the conversation.
                        id = "${p.tool}#$i@${Random.nextLong().toULong().toString(16)}",
                        tool = p.tool,
                        argumentsJson = p.arguments.toString(),
                        preview = p.preview,
                    )
                },
            )
        }

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

    private companion object {
        const val AI_REQUEST_TIMEOUT_MILLIS = 180_000L
        const val EXECUTE_TIMEOUT_MILLIS = 60_000L
    }
}
