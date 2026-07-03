package eu.anifantakis.ctv.server.routes

import eu.anifantakis.ctv.server.auth.AuthDb
import eu.anifantakis.ctv.server.plugins.AUTH_BEARER
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

@Serializable
data class LoginResponse(
    val token: String,
    val role: String,
    val displayName: String,
    val clientCode: String? = null
)

fun Route.authRoutes(authDb: AuthDb) {
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

            val token = withContext(Dispatchers.IO) { authDb.createToken(user.id) }
            call.respond(
                LoginResponse(
                    token = token,
                    role = user.role.name,
                    displayName = user.displayName,
                    clientCode = user.clientCode
                )
            )
        }

        // Tokens never expire, so logout = revocation: delete the row and the
        // token is dead on the very next request.
        authenticate(AUTH_BEARER) {
            post("/logout") {
                val token = call.request.headers[HttpHeaders.Authorization]
                    ?.removePrefix("Bearer")?.trim()
                if (!token.isNullOrEmpty()) {
                    withContext(Dispatchers.IO) { authDb.deleteToken(token) }
                }
                call.respond(mapOf("status" to "logged out"))
            }
        }
    }
}
