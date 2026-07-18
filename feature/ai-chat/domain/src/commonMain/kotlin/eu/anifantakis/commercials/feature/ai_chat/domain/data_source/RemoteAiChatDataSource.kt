package eu.anifantakis.commercials.feature.ai_chat.domain.data_source

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatMessage
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatReply
import eu.anifantakis.commercials.feature.ai_chat.domain.AiExecutionOutcome

interface RemoteAiChatDataSource {
    suspend fun send(
        history: List<AiChatMessage>,
        provider: String,
        model: String,
        screenContext: String?,
    ): DataResult<AiChatReply, RemoteError>

    suspend fun execute(
        tool: String,
        argumentsJson: String,
    ): DataResult<AiExecutionOutcome, RemoteError>
}
