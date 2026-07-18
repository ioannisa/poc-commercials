package eu.anifantakis.commercials.feature.ai_chat.domain.data_source

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatMessage
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatReply

interface RemoteAiChatDataSource {
    suspend fun send(
        history: List<AiChatMessage>,
        provider: String,
        model: String,
    ): DataResult<AiChatReply, RemoteError>
}
