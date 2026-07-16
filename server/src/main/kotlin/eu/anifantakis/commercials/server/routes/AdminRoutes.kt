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
}

/** UserRole.valueOf throws IllegalArgumentException -> 400 via StatusPages. */
private fun List<GrantDto>.toGrants(): List<StationGrant> =
    map { StationGrant(it.stationId, UserRole.valueOf(it.role), it.clientCode?.takeIf { c -> c.isNotBlank() }) }

private suspend fun io.ktor.server.application.ApplicationCall.userIdParam(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Numeric user id required in path"))
    }
    return id
}
