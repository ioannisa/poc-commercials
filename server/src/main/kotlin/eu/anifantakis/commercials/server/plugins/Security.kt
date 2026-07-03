package eu.anifantakis.commercials.server.plugins

import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.auth.AuthUser
import eu.anifantakis.commercials.server.auth.UserRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.ktor.ext.inject

const val AUTH_BEARER = "auth-bearer"

/**
 * Bearer-token authentication: every request under an `authenticate` block
 * must carry `Authorization: Bearer <token>` where the token exists in the
 * auth_tokens table. Unknown/revoked tokens -> 401 automatically.
 */
fun Application.configureSecurity() {
    val authDb by inject<AuthDb>()

    install(Authentication) {
        bearer(AUTH_BEARER) {
            realm = "POCCTV"
            authenticate { credential ->
                withContext(Dispatchers.IO) { authDb.findUserByToken(credential.token) }
            }
        }
    }
}

/** The authenticated user of this call (only valid inside `authenticate` blocks). */
fun ApplicationCall.authUser(): AuthUser =
    principal<AuthUser>() ?: error("No authenticated user on this call - route not under authenticate{}?")

/**
 * Responds 403 and returns false unless the caller has one of [roles].
 * Usage: `if (!call.requireRole(UserRole.NORMAL_USER)) return@get`
 */
suspend fun ApplicationCall.requireRole(vararg roles: UserRole): Boolean {
    val user = authUser()
    if (user.role in roles) return true
    respond(
        HttpStatusCode.Forbidden,
        mapOf("error" to "Requires role ${roles.joinToString(" or ")} (you are ${user.role})")
    )
    return false
}
