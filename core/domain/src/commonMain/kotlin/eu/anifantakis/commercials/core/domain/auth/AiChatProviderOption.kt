package eu.anifantakis.commercials.core.domain.auth

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * One AI-chat provider the server offers this session, as sent at login and
 * refreshed by the keep-alive: the provider id (`anthropic` | `openai` |
 * `gemini`) plus the models the user may pick for it (FIRST = default). The
 * server's catalog order is the dropdown order (default provider first); API
 * keys never travel - the server proxies every chat.
 *
 * Domain vocabulary like [StationAccess]: it appears on the [UserSession]
 * contract and the data layer persists it inside `StoredSession` (hence
 * `@Serializable`). `@Immutable` for the same reason - a read-only value the
 * chat chrome renders directly.
 */
@Immutable
@Serializable
data class AiChatProviderOption(
    val id: String,
    val models: List<String>,
)
