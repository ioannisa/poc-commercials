package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.mailer.renderPasswordResetEmail
import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.auth.AuthUser
import eu.anifantakis.commercials.server.auth.PASSWORD_RESET_TTL_SECONDS
import eu.anifantakis.commercials.server.plugins.AUTH_BEARER
import eu.anifantakis.commercials.server.plugins.authUser
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.http.*
import io.ktor.server.application.*
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
    /** After an admin reset / on a fresh account: the client must trap the user
     *  on a change-password screen until they pick a new one. */
    val mustChangePassword: Boolean = false,
    val stations: List<StationAccessDto>
)

/**
 * `GET /session`: the keep-alive's reply.
 *
 * [expiresInSeconds] is the life left in the caller's token, or null = it never
 * lapses. The client paces its heartbeat from this, so editing `session:` in
 * server.yaml retunes every client without a release.
 */
/**
 * The keep-alive's answer - and the ONLY thing that keeps a running client's
 * station list honest.
 *
 * [stations] is the CURRENT access list, recomputed per knock. Login used to be
 * the only place it was ever sent, so the client's list was a snapshot taken at
 * sign-in: a group migrated in afterwards was hosted, granted and reachable, yet
 * invisible until the operator logged out and back in. Restarting the server did
 * not help (it is not the server that is stale) and neither did restarting the
 * client (it just re-read the same stored snapshot).
 */
@Serializable
data class SessionInfoResponse(
    val expiresInSeconds: Long? = null,
    val stations: List<StationAccessDto> = emptyList(),
)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class ForgotPasswordRequest(val username: String)

@Serializable
data class ResetPasswordWithCodeRequest(val username: String, val code: String, val newPassword: String)

/**
 * The reset outcome as a machine-readable [status] the client switches on:
 * "ok" | "invalid_code" | "locked" | "expired". [retryAfterSeconds] is set when
 * the escalating lock is armed ("invalid_code") or already active ("locked").
 */
@Serializable
data class ResetResultResponse(val status: String, val retryAfterSeconds: Long? = null)

@Serializable
data class CreateApiTokenRequest(val name: String)

/** The raw personal access token - returned ONCE, at creation; only its hash is stored. */
@Serializable
data class CreateApiTokenResponse(val token: String)

@Serializable
data class ApiTokenDto(val id: Long, val name: String, val createdAt: String, val lastUsedAt: String? = null)

/** The raw bearer value, as the client holds it (the DB only ever sees its hash). */
private fun ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer")?.trim()?.ifEmpty { null }

/**
 * Grants for stations no longer in server.yaml are dropped - the display name
 * comes from the YAML, and a grant without a hosted station is unusable anyway.
 */
private fun StationRegistry.accessFor(user: AuthUser): List<StationAccessDto> =
    user.grants.mapNotNull { grant ->
        config(grant.stationId)?.let { station ->
            StationAccessDto(
                id = station.id,
                name = station.name,
                role = grant.role.name,
                clientCode = grant.clientCode,
            )
        }
    }

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

            val token = withContext(Dispatchers.IO) { authDb.createToken(user.id) }
            call.respond(
                LoginResponse(
                    token = token,
                    displayName = user.displayName,
                    isAdmin = user.isAdmin,
                    mustChangePassword = user.mustChangePassword,
                    stations = registry.accessFor(user),
                )
            )
        }

        // Open: start a "forgot password" flow. A 6-digit code is emailed to the
        // account's address (fire-and-forget). The response is ALWAYS the same -
        // it never reveals whether the username exists or has an email on file.
        post("/forgot") {
            val username = call.receive<ForgotPasswordRequest>().username.trim()
            val reset = withContext(Dispatchers.IO) { authDb.createPasswordReset(username) }
            reset?.let {
                call.application.sendAuthMail(
                    registry = registry,
                    intendedTo = it.email,
                    subject = "Επαναφορά κωδικού πρόσβασης",
                    html = renderPasswordResetEmail(it.code, (PASSWORD_RESET_TTL_SECONDS / 60).toInt()),
                )
            }
            call.respond(mapOf("status" to "if the account exists with an email on file, a reset code was sent"))
        }

        // Open: complete the flow with the emailed code. Enforces the escalating
        // lockout (see AuthDb.resetPasswordWithCode). A wrong username / expired /
        // absent request all read as "invalid_code" - never revealing which.
        post("/reset") {
            val request = call.receive<ResetPasswordWithCodeRequest>()
            val outcome = withContext(Dispatchers.IO) {
                authDb.resetPasswordWithCode(request.username.trim(), request.code.trim(), request.newPassword)
            }
            // Always 200: the [status] field carries the outcome, so the client
            // parses one shape and never has to read a 4xx body. ("invalid_code"
            // covers a wrong/expired-less/absent request too - anti-enumeration.)
            val result = when (outcome) {
                AuthDb.ResetOutcome.Success -> ResetResultResponse("ok")
                is AuthDb.ResetOutcome.Locked -> ResetResultResponse("locked", outcome.retryAfterSeconds)
                is AuthDb.ResetOutcome.InvalidCode -> ResetResultResponse("invalid_code", outcome.retryAfterSeconds)
                AuthDb.ResetOutcome.Expired -> ResetResultResponse("expired")
                AuthDb.ResetOutcome.NoRequest -> ResetResultResponse("invalid_code")
            }
            call.respond(result)
        }

        authenticate(AUTH_BEARER) {

            /**
             * THE HEARTBEAT - what makes "an app that is open is never logged out"
             * true. A token's window is pushed forward by USE
             * ([AuthDb.findUserByToken] slides it on every authenticated request),
             * but an app left OPEN and IDLE makes no requests at all: it would sit
             * there ageing and die on screen. So a running client knocks here.
             *
             * The handler deliberately does NOTHING. Passing the bearer auth to
             * reach it already ran findUserByToken, and that is what slid the
             * window. The reply only says when to knock again.
             *
             * ── Why it does not hand back a NEW token ──
             *
             * Because a token is shared by every live client of the same store, and
             * rotating it would kill the others. On the web that is a second browser
             * TAB: same origin, same localStorage, but each tab caches the token in
             * its own memory. Tab B rotates, the server retires the old value, and
             * Tab A - which still holds it - 401s, clears the shared store, and
             * takes Tab B down with it. Opening a second tab would log you out of
             * both. Sliding the window touches no client but the one that knocked.
             *
             * ── The one logout that is allowed ──
             *
             * Starting the app with a token that ALREADY lapsed. It fails the bearer
             * auth, never reaches here, 401s, and the user signs in. A credential
             * able to revive an EXPIRED session would BE the session and would never
             * expire - which is exactly why nothing here renews a dead token, and
             * why the client renews strictly BEFORE expiry, never after.
             *
             * So the lifetime measures how long the app may be CLOSED, not how long
             * it may be open.
             */
            get("/session") {
                val expiresIn = call.bearerToken()?.let {
                    withContext(Dispatchers.IO) { authDb.tokenExpiresInSeconds(it) }
                }
                // The live registry, not a login-time snapshot: a group added by a
                // migration is in it immediately (MigrationService.hostLive), so
                // the next knock is when the client learns about it.
                val user = call.authUser()
                call.respond(
                    SessionInfoResponse(
                        expiresInSeconds = expiresIn,
                        stations = user?.let { registry.accessFor(it) }.orEmpty(),
                    )
                )
            }

            // Logout = revocation: delete the row and the token is dead on the
            // very next request, window or no window.
            post("/logout") {
                call.bearerToken()?.let { token ->
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

            // Self-service personal access tokens (for MCP / API clients). Each
            // authenticates AS this user, so it carries the user's OWN per-station
            // grants and role - and never expires until revoked here.
            route("/api-tokens") {
                get {
                    val user = call.authUser()
                    val tokens = withContext(Dispatchers.IO) { authDb.listApiTokens(user.id) }
                    call.respond(tokens.map { ApiTokenDto(it.id, it.name, it.createdAt, it.lastUsedAt) })
                }
                post {
                    val user = call.authUser()
                    val name = call.receive<CreateApiTokenRequest>().name
                    val raw = withContext(Dispatchers.IO) { authDb.createApiToken(user.id, name) }
                    call.respond(HttpStatusCode.Created, CreateApiTokenResponse(raw))
                }
                delete("/{id}") {
                    val user = call.authUser()
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Numeric token id required"))
                        return@delete
                    }
                    val revoked = withContext(Dispatchers.IO) { authDb.revokeApiToken(user.id, id) }
                    if (revoked) call.respond(mapOf("status" to "revoked"))
                    else call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such token"))
                }
            }
        }
    }
}
