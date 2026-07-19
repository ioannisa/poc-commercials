package eu.anifantakis.commercials.feature.user_management.domain

import androidx.compose.runtime.Immutable
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError

/** One user's grant on one station, as managed by the super admin. */
@Immutable
data class UserGrant(val stationId: String, val role: String, val clientCode: String? = null)

/** A managed account as listed by the admin endpoints. */
@Immutable
data class ManagedUser(
    val id: Long,
    val username: String,
    val displayName: String,
    val email: String? = null,
    val isAdmin: Boolean = false,
    val grants: List<UserGrant> = emptyList(),
)

/**
 * The one-time temp password the server minted for a create or reset. Shown to
 * the admin ONCE (copyable); [emailSent] is whether the user was also emailed.
 */
data class TempPasswordResult(val tempPassword: String, val emailSent: Boolean)

/**
 * A workstation's token as the admin oversees it: which machine, whose identity
 * it runs as ([username] + [userRole] summary), and when it was last used.
 */
@Immutable
data class AdminApiToken(
    val id: Long,
    val workstationName: String,
    val userId: Long,
    val username: String,
    val userRole: String,
    val createdAt: String,
    val lastUsedAt: String?,
)

/**
 * An OAuth grant as the admin oversees it: which user connected which AI
 * client ([clientName]), when, and when last used. Per user × client app, not
 * per machine (see [eu.anifantakis.commercials.feature.auth.domain.model.OAuthGrant]).
 */
@Immutable
data class AdminOAuthToken(
    val id: Long,
    val userId: Long,
    val username: String,
    val clientName: String,
    val createdAt: String,
    val lastUsedAt: String?,
    /** Self-declared at consent - the AI account's e-mail (null on older grants). */
    val connectedAccount: String?,
    /** IP + browser that submitted the consent form (audit trail; null on older grants). */
    val consentIp: String?,
    val consentUserAgent: String?,
    /** Approval gates - the grant works only when BOTH are true. */
    val userApproved: Boolean = true,
    val adminApproved: Boolean = true,
)

/**
 * The global MCP state: the kill-switch [enabled] flag + how many PATs
 * ([tokenCount]) and OAuth grants ([oauthGrantCount]) exist.
 */
data class McpSettings(
    val enabled: Boolean,
    val tokenCount: Int,
    val oauthGrantCount: Int = 0,
    /** Global switch: every NEW OAuth grant needs super-admin approval. */
    val adminApprovalRequired: Boolean = false,
)

/**
 * The desktop auto-update advertisement, as the admin edits it: the latest
 * published version, the oldest still-supported one (older clients treat the
 * update as MANDATORY), and one installer URL per package format (absolute,
 * or server-relative like "/downloads/x.msi"). BLANK = unset: the field is
 * cleared server-side and clients simply aren't offered that piece.
 */
data class AppUpdateSettings(
    val latest: String = "",
    val minSupported: String = "",
    val dmg: String = "",
    val msi: String = "",
    val deb: String = "",
)

/**
 * Super-admin user management: list, create, reset password, edit
 * per-station grants, delete. Only the super administrator gets non-403
 * responses; their own account lives in server.yaml, not here.
 *
 * Create and reset do NOT take a password: the server mints a temporary one
 * (returned here for the admin to copy, and emailed to the user), and the
 * account must change it at first login.
 */
interface UserManagementRepository {
    suspend fun listUsers(): DataResult<List<ManagedUser>, RemoteError>
    suspend fun createUser(
        username: String,
        displayName: String,
        email: String?,
        grants: List<UserGrant>,
    ): DataResult<TempPasswordResult, RemoteError>
    suspend fun resetPassword(userId: Long): DataResult<TempPasswordResult, RemoteError>
    suspend fun setGrants(userId: Long, grants: List<UserGrant>): DataResult<Unit, RemoteError>
    suspend fun deleteUser(userId: Long): DataResult<Unit, RemoteError>

    /** Admin MCP oversight: every user's personal access tokens. */
    suspend fun listAllApiTokens(): DataResult<List<AdminApiToken>, RemoteError>

    /** Admin revoke of any token by id. */
    suspend fun revokeApiToken(tokenId: Long): DataResult<Unit, RemoteError>

    /** Admin MCP oversight: every user's OAuth grants (connected AI clients). */
    suspend fun listAllOAuthTokens(): DataResult<List<AdminOAuthToken>, RemoteError>

    /** Admin oversight: per-user AI-chat token usage, aggregated per (user, provider, model). */
    suspend fun aiUsage(): DataResult<List<AiUsageEntry>, RemoteError>

    /** Admin revoke of any OAuth grant by id. */
    suspend fun revokeOAuthToken(tokenId: Long): DataResult<Unit, RemoteError>

    /** Admin approval of a pending OAuth grant (clears both gates). */
    suspend fun approveOAuthToken(tokenId: Long): DataResult<Unit, RemoteError>

    /** Toggle the global "new AI connections need admin approval" switch. */
    suspend fun setOauthAdminApproval(required: Boolean): DataResult<Unit, RemoteError>

    /**
     * Admin "change role": repoint the token on [workstation] to [targetUserId]
     * WITHOUT changing the secret, so the machine keeps working under the new
     * identity/role and needs no local reconfiguration.
     */
    suspend fun reassignApiToken(workstation: String, targetUserId: Long): DataResult<Unit, RemoteError>

    /** The global MCP kill switch + token count. */
    suspend fun getMcpSettings(): DataResult<McpSettings, RemoteError>

    suspend fun setMcpEnabled(enabled: Boolean): DataResult<Unit, RemoteError>

    /** The desktop auto-update advertisement (served openly by GET /version). */
    suspend fun getAppUpdateSettings(): DataResult<AppUpdateSettings, RemoteError>

    /** Publish the advertisement: the form replaces all fields (blank clears). */
    suspend fun setAppUpdateSettings(settings: AppUpdateSettings): DataResult<Unit, RemoteError>
}

/** One aggregated AI usage row (lifetime totals per user x provider x model). */
@Immutable
data class AiUsageEntry(
    val username: String,
    val provider: String,
    val model: String,
    val requests: Long,
    val inputTokens: Long,
    val outputTokens: Long,
)
