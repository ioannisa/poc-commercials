package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.mailer.renderPasswordResetEmail
import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.auth.AuthUser
import eu.anifantakis.commercials.server.auth.PASSWORD_RESET_TTL_SECONDS
import eu.anifantakis.commercials.server.plugins.AUTH_BEARER
import eu.anifantakis.commercials.server.plugins.CREDENTIALS_RATE_LIMIT
import eu.anifantakis.commercials.server.plugins.authUser
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.rateLimit
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
    /** Server-wide: whether this server serves the OpenAPI/Swagger UI (server.yaml
     *  `swagger`). The super-admin "API Docs" link is disabled when this is false. */
    val swaggerEnabled: Boolean = false,
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
    /** Server-wide Swagger toggle, carried on every keep-alive knock so a running
     *  client that skipped login (persisted token) tracks a server.yaml change. */
    val swaggerEnabled: Boolean = false,
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
data class CreateApiTokenRequest(val workstation: String, val confirmTakeover: Boolean = false)

/** The raw personal access token - returned ONCE, at creation; only its hash is stored. */
@Serializable
data class CreateApiTokenResponse(val token: String)

@Serializable
data class ApiTokenDto(val id: Long, val workstationName: String, val createdAt: String, val lastUsedAt: String? = null)

/** A workstation name's availability for the caller: FREE, MINE, or OTHER. */
@Serializable
data class WorkstationAvailabilityDto(val status: String)

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

        // The three OPEN endpoints are the password-guessing surface - per-IP
        // throttled (10/min; see RateLimiting.kt). The bearer-authed routes
        // below are not: a valid token already gates them.
        rateLimit(CREDENTIALS_RATE_LIMIT) {

        /**
         * Log in with username and password to obtain a bearer token.
         * Open endpoint (no auth): this is how a client GETs a token.
         *
         * Tag: Auth
         */
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
                    swaggerEnabled = registry.swaggerEnabled,
                    mustChangePassword = user.mustChangePassword,
                    stations = registry.accessFor(user),
                )
            )
        }

        /**
         * Start a forgot-password flow; email a 6-digit reset code (reply never reveals if the account exists).
         *
         * Tag: Auth
         */
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

        /**
         * Complete a password reset with the emailed code, enforcing the escalating lockout.
         *
         * Tag: Auth
         */
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

        }   // end rateLimit(CREDENTIALS_RATE_LIMIT)

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
             *
             * Tag: Auth
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
                        swaggerEnabled = registry.swaggerEnabled,
                    )
                )
            }

            /**
             * Log out by revoking the caller's token so it dies on the next request.
             *
             * Tag: Auth
             */
            post("/logout") {
                call.bearerToken()?.let { token ->
                    withContext(Dispatchers.IO) { authDb.deleteToken(token) }
                }
                call.respond(mapOf("status" to "logged out"))
            }

            /**
             * Change the caller's own password, revoking all of their sessions.
             *
             * Tag: Auth
             */
            post("/password") {
                val request = call.receive<ChangePasswordRequest>()
                val user = call.authUser()
                withContext(Dispatchers.IO) {
                    authDb.changePassword(user.id, request.currentPassword, request.newPassword)
                }
                call.respond(mapOf("status" to "password changed - please log in again"))
            }

            // Self-service personal access tokens (for MCP / API clients). Each is
            // bound to a WORKSTATION (the machine label) and authenticates AS this
            // user, carrying the user's OWN per-station grants and role - never
            // expiring until revoked here or replaced by a new mint for the same
            // workstation. At most one token exists per workstation.
            route("/api-tokens") {
                /**
                 * List the caller's personal access tokens (at most one per workstation).
                 *
                 * Tag: MCP
                 */
                get {
                    val user = call.authUser()
                    val tokens = withContext(Dispatchers.IO) { authDb.listApiTokens(user.id) }
                    call.respond(tokens.map { ApiTokenDto(it.id, it.workstationName, it.createdAt, it.lastUsedAt) })
                }
                /**
                 * Report a workstation name's availability to the caller as FREE, MINE, or OTHER.
                 *
                 * Tag: MCP
                 */
                get("/availability") {
                    val user = call.authUser()
                    val workstation = call.request.queryParameters["workstation"].orEmpty().trim()
                    if (workstation.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "workstation query parameter required"))
                        return@get
                    }
                    val status = withContext(Dispatchers.IO) { authDb.apiTokenWorkstationStatus(workstation, user.id) }
                    call.respond(WorkstationAvailabilityDto(status.name))
                }
                /**
                 * Mint a personal access token for a workstation; requires confirmTakeover to seize an OTHER-owned name.
                 *
                 * Tag: MCP
                 */
                post {
                    val user = call.authUser()
                    val req = call.receive<CreateApiTokenRequest>()
                    val workstation = req.workstation.trim()
                    if (workstation.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "workstation must not be blank"))
                        return@post
                    }
                    // Taking over ANOTHER user's workstation needs an explicit confirm
                    // (the UI warns first). Guard it here too, not just client-side.
                    val status = withContext(Dispatchers.IO) { authDb.apiTokenWorkstationStatus(workstation, user.id) }
                    if (status == AuthDb.WorkstationStatus.OTHER && !req.confirmTakeover) {
                        call.respond(HttpStatusCode.Conflict, WorkstationAvailabilityDto(status.name))
                        return@post
                    }
                    val raw = withContext(Dispatchers.IO) { authDb.createApiToken(user.id, workstation) }
                    call.respond(HttpStatusCode.Created, CreateApiTokenResponse(raw))
                }
                /**
                 * Revoke one of the caller's personal access tokens by its numeric id.
                 *
                 * Tag: MCP
                 */
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
