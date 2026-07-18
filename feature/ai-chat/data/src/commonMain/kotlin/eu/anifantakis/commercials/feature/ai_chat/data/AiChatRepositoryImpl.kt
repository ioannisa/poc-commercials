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
    ): DataResult<AiChatReply, RemoteError> =
        remoteDataSource.send(history, provider, model)

    override suspend fun execute(
        tool: String,
        argumentsJson: String,
    ): DataResult<AiExecutionOutcome, RemoteError> =
        remoteDataSource.execute(tool, argumentsJson)
}
