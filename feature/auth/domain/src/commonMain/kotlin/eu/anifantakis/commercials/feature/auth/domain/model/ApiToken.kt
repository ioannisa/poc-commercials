package eu.anifantakis.commercials.feature.auth.domain.model

import androidx.compose.runtime.Immutable

/** A personal access token as its owner lists it - never the secret itself. */
@Immutable
data class ApiToken(
    val id: Long,
    val workstationName: String,
    val createdAt: String,
    val lastUsedAt: String?,
)

/**
 * Whether a workstation name is unclaimed, already the caller's, or held by
 * ANOTHER user - the self-service availability check. OTHER never names who.
 */
enum class WorkstationAvailability { FREE, MINE, OTHER }

/**
 * A freshly minted token: the RAW secret (shown ONCE) plus the MCP SSE URL the
 * client should point at. The pair is everything a user needs to wire up an MCP
 * client, so the UI can offer a ready "copy config" from it.
 */
data class CreatedApiToken(
    val token: String,
    val mcpUrl: String,
)

/**
 * One of the caller's OWN OAuth grants - a native AI connector (Claude,
 * ChatGPT, Gemini, ...) they authorized through the browser login. Unlike a
 * PAT this is NOT per-machine: the vendor connector is account-level, so one
 * grant covers that client's web/desktop/mobile. [clientName] is what the
 * client declared at registration.
 */
@Immutable
data class OAuthGrant(
    val id: Long,
    val clientName: String,
    val createdAt: String,
    val lastUsedAt: String?,
)
