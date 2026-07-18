package eu.anifantakis.commercials.feature.ai_chat.data

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatMessage
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatReply
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatRepository
import eu.anifantakis.commercials.feature.ai_chat.domain.AiExecutionOutcome
import eu.anifantakis.commercials.feature.ai_chat.domain.data_source.RemoteAiChatDataSource

class AiChatRepositoryImpl(
    private val remoteDataSource: RemoteAiChatDataSource,
) : AiChatRepository {

    override suspend fun send(
        history: List<AiChatMessage>,
        provider: String,
        model: String,
        screenContext: String?,
    ): DataResult<AiChatReply, RemoteError> =
        remoteDataSource.send(history, provider, model, screenContext)

    override suspend fun sendStreaming(
        history: List<AiChatMessage>,
        provider: String,
        model: String,
        screenContext: String?,
        onStep: (String) -> Unit,
    ): DataResult<AiChatReply, RemoteError> =
        remoteDataSource.sendStreaming(history, provider, model, screenContext, onStep)

    override suspend fun execute(
        tool: String,
        argumentsJson: String,
    ): DataResult<AiExecutionOutcome, RemoteError> =
        remoteDataSource.execute(tool, argumentsJson)
}
