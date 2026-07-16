package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.mailer.renderTempPasswordEmail
import eu.anifantakis.commercials.server.auth.AuthDb
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
data class McpSettingsDto(val enabled: Boolean, val tokenCount: Int)

@Serializable
data class SetMcpEnabledRequest(val enabled: Boolean)

@Serializable
data class SetGrantsRequest(val grants: List<GrantDto>)

/**
 * User management - super administrator only (the config-managed account
 * from server.yaml). Validation failures (bad username/password/grants,
 * duplicates, protected super-admin row) surface as 400 via StatusPages.
 */
fun Route.adminRoutes(authDb: AuthDb, registry: StationRegistry) {
    route("/api/admin/users") {

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

        // Create: the system mints a TEMP password (not admin-chosen). It is
        // returned once for the admin to copy, and emailed to the user if an
        // address was given; either way the new account must change it at first login.
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
                    html = renderTempPasswordEmail(request.username.trim(), created.tempPassword, newAccount = true),
                )
            }
            call.respond(
                HttpStatusCode.Created,
                TempPasswordResponse(created.id, created.tempPassword, created.email != null),
            )
        }

        // Admin reset: mint a TEMP password (returned for the admin to copy, and
        // emailed to the user), force a change at next login, clear any lockout,
        // revoke all sessions. The mail-server-independent break-glass path.
        post("/{id}/reset") {
            if (!call.requireAdmin()) return@post
            val userId = call.userIdParam() ?: return@post
            val temp = withContext(Dispatchers.IO) { authDb.adminResetPassword(userId) }
            temp.email?.let { email ->
                call.application.sendAuthMail(
                    registry = registry,
                    intendedTo = email,
                    subject = "Ο κωδικός σας μηδενίστηκε",
                    html = renderTempPasswordEmail(temp.username, temp.password, newAccount = false),
                )
            }
            call.respond(TempPasswordResponse(userId, temp.password, temp.email != null))
        }

        put("/{id}/grants") {
            if (!call.requireAdmin()) return@put
            val userId = call.userIdParam() ?: return@put
            val request = call.receive<SetGrantsRequest>()
            withContext(Dispatchers.IO) { authDb.setGrants(userId, request.grants.toGrants()) }
            call.respond(mapOf("status" to "grants updated"))
        }

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
        // Change role: repoint a workstation's token to another user WITHOUT
        // changing the secret - the machine's config stays valid, only the identity
        // (and thus grants/role) behind the token changes.
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
        delete("/{id}") {
            if (!call.requireAdmin()) return@delete
            val id = call.userIdParam() ?: return@delete
            val revoked = withContext(Dispatchers.IO) { authDb.revokeApiTokenById(id) }
            if (revoked) call.respond(mapOf("status" to "revoked"))
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such token"))
        }
    }

    // The global MCP kill switch + a token-count status.
    route("/api/admin/mcp-settings") {
        get {
            if (!call.requireAdmin()) return@get
            val enabled = withContext(Dispatchers.IO) { authDb.isMcpEnabled() }
            val count = withContext(Dispatchers.IO) { authDb.apiTokenCount() }
            call.respond(McpSettingsDto(enabled, count))
        }
        put {
            if (!call.requireAdmin()) return@put
            val request = call.receive<SetMcpEnabledRequest>()
            withContext(Dispatchers.IO) { authDb.setMcpEnabled(request.enabled) }
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
