package eu.anifantakis.commercials.feature.auth.domain.model

/** A personal access token as its owner lists it - never the secret itself. */
data class ApiToken(
    val id: Long,
    val name: String,
    val createdAt: String,
    val lastUsedAt: String?,
)

/**
 * A freshly minted token: the RAW secret (shown ONCE) plus the MCP SSE URL the
 * client should point at. The pair is everything a user needs to wire up an MCP
 * client, so the UI can offer a ready "copy config" from it.
 */
data class CreatedApiToken(
    val token: String,
    val mcpUrl: String,
)
