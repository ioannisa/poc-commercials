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
