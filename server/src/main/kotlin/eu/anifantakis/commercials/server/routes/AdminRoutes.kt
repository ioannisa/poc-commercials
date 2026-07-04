package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.auth.StationGrant
import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.plugins.requireAdmin
import io.ktor.http.*
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
    val isAdmin: Boolean,
    val grants: List<GrantDto>
)

@Serializable
data class CreateUserRequest(
    val username: String,
    val displayName: String,
    val password: String,
    val grants: List<GrantDto> = emptyList()
)

@Serializable
data class ResetPasswordRequest(val newPassword: String)

@Serializable
data class SetGrantsRequest(val grants: List<GrantDto>)

/**
 * User management - super administrator only (the config-managed account
 * from server.yaml). Validation failures (bad username/password/grants,
 * duplicates, protected super-admin row) surface as 400 via StatusPages.
 */
fun Route.adminRoutes(authDb: AuthDb) {
    route("/api/admin/users") {

        get {
            if (!call.requireAdmin()) return@get
            val users = withContext(Dispatchers.IO) { authDb.listUsers() }
            call.respond(users.map { user ->
                AdminUserDto(
                    id = user.id,
                    username = user.username,
                    displayName = user.displayName,
                    isAdmin = user.isAdmin,
                    grants = user.grants.map { GrantDto(it.stationId, it.role.name, it.clientCode) }
                )
            })
        }

        post {
            if (!call.requireAdmin()) return@post
            val request = call.receive<CreateUserRequest>()
            val id = withContext(Dispatchers.IO) {
                authDb.createUser(
                    username = request.username.trim(),
                    displayName = request.displayName.trim(),
                    password = request.password,
                    grants = request.grants.toGrants()
                )
            }
            call.respond(HttpStatusCode.Created, mapOf("id" to id))
        }

        put("/{id}/password") {
            if (!call.requireAdmin()) return@put
            val userId = call.userIdParam() ?: return@put
            val request = call.receive<ResetPasswordRequest>()
            withContext(Dispatchers.IO) { authDb.resetPassword(userId, request.newPassword) }
            call.respond(mapOf("status" to "password reset - the user's sessions were revoked"))
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
