package eu.anifantakis.commercials.feature.ai_chat.data.data_source

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.data.network.remoteCall
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.domain.util.map
import eu.anifantakis.commercials.feature.ai_chat.data.dto.AiChatRequestDto
import eu.anifantakis.commercials.feature.ai_chat.data.dto.AiChatResponseDto
import eu.anifantakis.commercials.feature.ai_chat.data.dto.AiChatTurnDto
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatMessage
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatReply
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatRole
import eu.anifantakis.commercials.feature.ai_chat.domain.AiToolStep
import eu.anifantakis.commercials.feature.ai_chat.domain.data_source.RemoteAiChatDataSource
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
            )
        }

    private companion object {
        const val AI_REQUEST_TIMEOUT_MILLIS = 180_000L
    }
}
