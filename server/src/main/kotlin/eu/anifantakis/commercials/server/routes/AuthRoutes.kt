package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.plugins.AUTH_BEARER
import eu.anifantakis.commercials.server.plugins.authUser
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

/** One station this user may access, with their role on it. */
@Serializable
data class StationAccessDto(
    val id: String,
    val name: String,
    val role: String,
    val clientCode: String? = null,
)

@Serializable
data class LoginResponse(
    val token: String,
    val displayName: String,
    val isAdmin: Boolean = false,
    val stations: List<StationAccessDto>
)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class RecoverPasswordRequest(val username: String, val recoveryCode: String, val newPassword: String)

@Serializable
data class RecoveryCodesResponse(val codes: List<String>)

fun Route.authRoutes(authDb: AuthDb, registry: StationRegistry) {
    route("/api/auth") {

        // Open: this is how you GET a token
        post("/login") {
            val request = call.receive<LoginRequest>()

            val user = withContext(Dispatchers.IO) {
                authDb.verifyCredentials(request.username, request.password)
            }
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid username or password"))
                return@post
            }

            // Grants for stations no longer in server.yaml are dropped -
            // the display name comes from the YAML, and a grant without a
            // hosted station is unusable anyway.
            val stations = user.grants.mapNotNull { grant ->
                registry.config(grant.stationId)?.let { station ->
                    StationAccessDto(
                        id = station.id,
                        name = station.name,
                        role = grant.role.name,
                        clientCode = grant.clientCode
                    )
                }
            }

            val token = withContext(Dispatchers.IO) { authDb.createToken(user.id) }
            call.respond(
                LoginResponse(
                    token = token,
                    displayName = user.displayName,
                    isAdmin = user.isAdmin,
                    stations = stations
                )
            )
        }

        // Open: "forgot password" - a one-time recovery code sets a new
        // password (and consumes the code + revokes all sessions). The error
        // is deliberately generic: never reveal whether the username exists.
        post("/recover") {
            val request = call.receive<RecoverPasswordRequest>()
            val recovered = withContext(Dispatchers.IO) {
                authDb.recoverPassword(request.username, request.recoveryCode, request.newPassword)
            }
            if (recovered) {
                call.respond(mapOf("status" to "password reset - please log in"))
            } else {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid username or recovery code")
                )
            }
        }

        authenticate(AUTH_BEARER) {
            // Tokens never expire, so logout = revocation: delete the row and
            // the token is dead on the very next request.
            post("/logout") {
                val token = call.request.headers[HttpHeaders.Authorization]
                    ?.removePrefix("Bearer")?.trim()
                if (!token.isNullOrEmpty()) {
                    withContext(Dispatchers.IO) { authDb.deleteToken(token) }
                }
                call.respond(mapOf("status" to "logged out"))
            }

            // Self-service password change; revokes ALL of the user's
            // sessions, so the client should return to the login screen.
            post("/password") {
                val request = call.receive<ChangePasswordRequest>()
                val user = call.authUser()
                withContext(Dispatchers.IO) {
                    authDb.changePassword(user.id, request.currentPassword, request.newPassword)
                }
                call.respond(mapOf("status" to "password changed - please log in again"))
            }

            // Regenerates the caller's one-time recovery codes. The raw codes
            // appear ONLY in this response - the server stores hashes.
            post("/recovery-codes") {
                val user = call.authUser()
                val codes = withContext(Dispatchers.IO) { authDb.generateRecoveryCodes(user.id) }
                call.respond(RecoveryCodesResponse(codes))
            }
        }
    }
}
