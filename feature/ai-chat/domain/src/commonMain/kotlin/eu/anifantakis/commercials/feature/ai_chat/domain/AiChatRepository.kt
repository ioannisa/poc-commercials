package eu.anifantakis.commercials.feature.ai_chat.domain

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError

/**
 * The in-app AI assistant. Stateless per request: the caller sends the visible
 * conversation so far and receives the assistant's next turn. The server runs
 * the model and its tools as the logged-in user - grants apply unchanged.
 *
 * [provider]/[model] are the user's dropdown picks from the session's provider
 * catalog ([eu.anifantakis.commercials.core.domain.auth.AiChatProviderOption]);
 * the server validates both against server.yaml and rejects anything outside it.
 */
interface AiChatRepository {
    suspend fun send(
        history: List<AiChatMessage>,
        provider: String,
        model: String,
    ): DataResult<AiChatReply, RemoteError>
}
