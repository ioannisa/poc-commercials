package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.mailer.renderTempPasswordEmail
import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.auth.OAuthDb
import eu.anifantakis.commercials.server.auth.StationGrant
import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.plugins.requireAdmin
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class GrantDto(val stationId: String, val role: String, val clientCode: String? = null)

@Serializable
data class AdminUserDto(
    val id: Long,
    val username: String,
    val displayName: String,
    val email: String? = null,
    val isAdmin: Boolean,
    val grants: List<GrantDto>
)

@Serializable
data class CreateUserRequest(
    val username: String,
    val displayName: String,
    val email: String? = null,
    val grants: List<GrantDto> = emptyList()
)

/**
 * The one-time temp password to show the admin (copyable), from a create or a
 * reset. [emailSent] is whether a notice was also dispatched to the user - the
 * password is on screen regardless, so the admin can relay it if mail is down.
 */
@Serializable
data class TempPasswordResponse(val id: Long, val tempPassword: String, val emailSent: Boolean)

@Serializable
data class AdminApiTokenDto(
    val id: Long,
    val workstationName: String,
    val userId: Long,
    val username: String,
    val userRole: String,
    val createdAt: String,
    val lastUsedAt: String? = null,
)

/** Admin "change role": repoint a workstation's token to another user (same secret). */
@Serializable
data class ReassignApiTokenRequest(val workstation: String, val targetUserId: Long)

@Serializable
data class McpSettingsDto(
    val enabled: Boolean,
    val tokenCount: Int,
    /** Live OAuth grants (native-connector logins), counted separately from PATs. */
    val oauthGrantCount: Int = 0,
    /** Global switch: every NEW OAuth grant needs super-admin approval. */
    val adminApprovalRequired: Boolean = false,
)

/** One OAuth grant (a native MCP connector a user logged in from), for admin oversight. */
@Serializable
data class AdminOAuthTokenDto(
    val id: Long,
    val userId: Long,
    val username: String,
    val clientName: String,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val refreshExpiresAt: String,
    /** Self-declared at consent - the AI account's e-mail (NULL on pre-migration grants). */
    val connectedAccount: String? = null,
    /** IP + browser that submitted the consent form (audit trail). */
    val consentIp: String? = null,
    val consentUserAgent: String? = null,
    /** Approval gates - the grant works only when BOTH are true. */
    val userApproved: Boolean = true,
    val adminApproved: Boolean = true,
)

/** Partial update: only the non-null fields are applied. */
@Serializable
data class SetMcpEnabledRequest(
    val enabled: Boolean? = null,
    val adminApprovalRequired: Boolean? = null,
)

@Serializable
data class SetGrantsRequest(val grants: List<GrantDto>)

/**
 * User management - super administrator only (the config-managed account
 * from server.yaml). Validation failures (bad username/password/grants,
 * duplicates, protected super-admin row) surface as 400 via StatusPages.
 */
fun Route.adminRoutes(authDb: AuthDb, oauthDb: OAuthDb, registry: StationRegistry) {
    route("/api/admin/users") {

        /**
         * List all users with their display info, admin flag, and station grants.
         *
         * Tag: Admin
         */
        get {
            if (!call.requireAdmin()) return@get
            val users = withContext(Dispatchers.IO) { authDb.listUsers() }
            call.respond(users.map { user ->
                AdminUserDto(
                    id = user.id,
                    username = user.username,
                    displayName = user.displayName,
                    email = user.email,
                    isAdmin = user.isAdmin,
                    grants = user.grants.map { GrantDto(it.stationId, it.role.name, it.clientCode) }
                )
            })
        }

        /**
         * Create a user with a system-minted one-time temp password, returned to the
         * admin to copy and emailed to the user if an address was given; the account
         * must change it at first login.
         *
         * Tag: Admin
         */
        post {
            if (!call.requireAdmin()) return@post
            val request = call.receive<CreateUserRequest>()
            val created = withContext(Dispatchers.IO) {
                authDb.createUserWithTempPassword(
                    username = request.username.trim(),
                    displayName = request.displayName.trim(),
                    email = request.email?.trim(),
                    grants = request.grants.toGrants(),
                )
            }
            created.email?.let { email ->
                call.application.sendAuthMail(
                    registry = registry,
                    intendedTo = email,
                    subject = "Ο λογαριασμός σας δημιουργήθηκε",
                    html = renderTempPasswordEmail(
                        // The groups the user was actually placed in - resolved from
                        // the grants just assigned, not every group the server hosts.
                        orgName = registry.brandNameFor(request.grants.map { it.stationId }),
                        username = request.username.trim(),
                        tempPassword = created.tempPassword,
                        newAccount = true,
                        // Dynamic per installation: where to sign in on the web
                        // and how to attach an AI connector, straight from server.yaml.
                        webUrl = registry.publicBaseUrl,
                        mcpUrl = registry.mcpConnectorUrl,
                    ),
                )
            }
            call.respond(
                HttpStatusCode.Created,
                TempPasswordResponse(created.id, created.tempPassword, created.email != null),
            )
        }

        /**
         * Admin-reset a user's password to a one-time temp (returned to copy and
         * emailed), forcing a change at next login while clearing lockout and revoking
         * all sessions.
         *
         * Tag: Admin
         */
        post("/{id}/reset") {
            if (!call.requireAdmin()) return@post
            val userId = call.userIdParam() ?: return@post
            val temp = withContext(Dispatchers.IO) { authDb.adminResetPassword(userId) }
            temp.email?.let { email ->
                call.application.sendAuthMail(
                    registry = registry,
                    intendedTo = email,
                    subject = "Ο κωδικός σας μηδενίστηκε",
                    html = renderTempPasswordEmail(
                        orgName = registry.brandNameFor(temp.stationIds),
                        username = temp.username,
                        tempPassword = temp.password,
                        newAccount = false,
                    ),
                )
            }
            call.respond(TempPasswordResponse(userId, temp.password, temp.email != null))
        }

        /**
         * Replace a user's station grants with the supplied set.
         *
         * Tag: Admin
         */
        put("/{id}/grants") {
            if (!call.requireAdmin()) return@put
            val userId = call.userIdParam() ?: return@put
            val request = call.receive<SetGrantsRequest>()
            withContext(Dispatchers.IO) { authDb.setGrants(userId, request.grants.toGrants()) }
            call.respond(mapOf("status" to "grants updated"))
        }

        /**
         * Delete a user by id.
         *
         * Tag: Admin
         */
        delete("/{id}") {
            if (!call.requireAdmin()) return@delete
            val userId = call.userIdParam() ?: return@delete
            withContext(Dispatchers.IO) { authDb.deleteUser(userId) }
            call.respond(mapOf("status" to "user deleted"))
        }
    }

    // Admin oversight of MCP/API personal access tokens: see every workstation's
    // token + its owner's role, revoke any, or repoint one to another user.
    route("/api/admin/api-tokens") {
        /**
         * List every workstation MCP/API token with its owner's username and role.
         *
         * Tag: MCP
         */
        get {
            if (!call.requireAdmin()) return@get
            val (tokens, users) = withContext(Dispatchers.IO) {
                authDb.listAllApiTokens() to authDb.listUsers()
            }
            val roleByUserId = users.associate { it.id to roleSummary(it) }
            call.respond(
                tokens.map {
                    AdminApiTokenDto(
                        id = it.id,
                        workstationName = it.workstationName,
                        userId = it.userId,
                        username = it.username,
                        userRole = roleByUserId[it.userId] ?: "-",
                        createdAt = it.createdAt,
                        lastUsedAt = it.lastUsedAt,
                    )
                }
            )
        }
        /**
         * Repoint a workstation's token to another user without changing the secret,
         * so the machine's config stays valid while the identity, grants, and role
         * behind the token change.
         *
         * Tag: MCP
         */
        post("/reassign") {
            if (!call.requireAdmin()) return@post
            val req = call.receive<ReassignApiTokenRequest>()
            if (req.workstation.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "workstation must not be blank"))
                return@post
            }
            val previousOwner = withContext(Dispatchers.IO) {
                authDb.reassignApiToken(req.workstation, req.targetUserId)
            }
            if (previousOwner == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No token for that workstation"))
                return@post
            }
            call.application.log.info(
                "MCP token for workstation '{}' reassigned from user {} to user {} by admin",
                req.workstation, previousOwner, req.targetUserId,
            )
            call.respond(mapOf("status" to "reassigned"))
        }
        /**
         * Revoke a workstation MCP/API token by id.
         *
         * Tag: MCP
         */
        delete("/{id}") {
            if (!call.requireAdmin()) return@delete
            val id = call.userIdParam() ?: return@delete
            val revoked = withContext(Dispatchers.IO) { authDb.revokeApiTokenById(id) }
            if (revoked) call.respond(mapOf("status" to "revoked"))
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such token"))
        }
    }

    // Admin oversight of OAuth grants: every native-connector login (which
    // user, which client app, when last used), each revocable. The kill
    // switch below covers these too - findByOAuthToken shares the gate.
    route("/api/admin/oauth-tokens") {
        /**
         * List every OAuth grant with its owner and the client application's name.
         *
         * Tag: MCP
         */
        get {
            if (!call.requireAdmin()) return@get
            val grants = withContext(Dispatchers.IO) { oauthDb.listAllTokens() }
            call.respond(
                grants.map {
                    AdminOAuthTokenDto(
                        id = it.id,
                        userId = it.userId,
                        username = it.username,
                        clientName = it.clientName,
                        createdAt = it.createdAt,
                        lastUsedAt = it.lastUsedAt,
                        refreshExpiresAt = it.refreshExpiresAt,
                        connectedAccount = it.connectedAccount,
                        consentIp = it.consentIp,
                        consentUserAgent = it.consentUserAgent,
                        userApproved = it.userApproved,
                        adminApproved = it.adminApproved,
                    )
                }
            )
        }
        /**
         * Revoke an OAuth grant by id (the connector must re-authenticate).
         *
         * Tag: MCP
         */
        delete("/{id}") {
            if (!call.requireAdmin()) return@delete
            val id = call.userIdParam() ?: return@delete
            val revoked = withContext(Dispatchers.IO) { oauthDb.revokeTokenById(id) }
            if (revoked) call.respond(mapOf("status" to "revoked"))
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such grant"))
        }
        /**
         * Approve a pending OAuth grant (clears BOTH gates - the admin outranks the e-mail link).
         *
         * Tag: MCP
         */
        post("/{id}/approve") {
            if (!call.requireAdmin()) return@post
            val id = call.userIdParam() ?: return@post
            val approved = withContext(Dispatchers.IO) { oauthDb.adminApproveById(id) }
            if (approved) call.respond(mapOf("status" to "approved"))
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such grant"))
        }
    }

    // The global MCP kill switch + a token-count status.
    route("/api/admin/mcp-settings") {
        /**
         * Return the global MCP enabled flag and the active PAT + OAuth grant counts.
         *
         * Tag: MCP
         */
        get {
            if (!call.requireAdmin()) return@get
            val enabled = withContext(Dispatchers.IO) { authDb.isMcpEnabled() }
            val count = withContext(Dispatchers.IO) { authDb.apiTokenCount() }
            val oauthCount = withContext(Dispatchers.IO) { oauthDb.oauthTokenCount() }
            val adminApproval = withContext(Dispatchers.IO) { authDb.isOauthAdminApprovalRequired() }
            call.respond(McpSettingsDto(enabled, count, oauthCount, adminApproval))
        }
        /**
         * Update the global MCP switches: the kill switch and/or the new-grant admin-approval requirement.
         *
         * Tag: MCP
         */
        put {
            if (!call.requireAdmin()) return@put
            val request = call.receive<SetMcpEnabledRequest>()
            withContext(Dispatchers.IO) {
                request.enabled?.let { authDb.setMcpEnabled(it) }
                request.adminApprovalRequired?.let { authDb.setOauthAdminApprovalRequired(it) }
            }
            call.respond(mapOf("status" to "updated"))
        }
    }
}

/** UserRole.valueOf throws IllegalArgumentException -> 400 via StatusPages. */
private fun List<GrantDto>.toGrants(): List<StationGrant> =
    map { StationGrant(it.stationId, UserRole.valueOf(it.role), it.clientCode?.takeIf { c -> c.isNotBlank() }) }

/** A compact role label for the admin token table: super admin, or the distinct grant roles. */
private fun roleSummary(u: AuthDb.UserSummary): String = when {
    u.isAdmin -> "SUPER_ADMIN"
    u.grants.isEmpty() -> "-"
    else -> u.grants.map { it.role.name }.distinct().joinToString(", ")
}

private suspend fun io.ktor.server.application.ApplicationCall.userIdParam(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Numeric user id required in path"))
    }
    return id
}
