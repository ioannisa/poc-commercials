package eu.anifantakis.commercials.server.plugins

import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.auth.AuthUser
import eu.anifantakis.commercials.server.auth.StationGrant
import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.scheduler.StationDb
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.path
import io.ktor.server.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.ktor.ext.inject

const val AUTH_BEARER = "auth-bearer"

/**
 * HTTP Basic auth guarding the /swagger docs. The API itself is bearer-only,
 * but Swagger UI is reached by a browser NAVIGATION, which carries no
 * Authorization header - so the one scheme a browser answers natively (a
 * username/password dialog) is the only way to gate it. Validated against the
 * users table and accepted ONLY for the super administrator; credentials
 * travel over the proxy's TLS. See configureRouting's swaggerUI mount.
 */
const val AUTH_SWAGGER_BASIC = "auth-swagger-basic"

/**
 * Bearer-token authentication: every request under an `authenticate` block
 * must carry `Authorization: Bearer <token>` where the token exists in the
 * auth_tokens table. Unknown/revoked tokens -> 401 automatically. The
 * principal carries the user's per-station grants.
 */
fun Application.configureSecurity() {
    val authDb by inject<AuthDb>()

    install(Authentication) {
        bearer(AUTH_BEARER) {
            realm = "Commercials Manager"
            authenticate { credential ->
                withContext(Dispatchers.IO) { authDb.findUserByToken(credential.token) }
            }
        }
        basic(AUTH_SWAGGER_BASIC) {
            realm = "Commercials Manager API docs"
            validate { credential ->
                withContext(Dispatchers.IO) {
                    // Super-admin only: verifyCredentials returns the user (with
                    // its is_admin flag); anyone who is not the admin is refused
                    // so the browser re-challenges.
                    authDb.verifyCredentials(credential.name, credential.password)
                        ?.takeIf { it.isAdmin }
                }
            }
        }
    }
}

/** The authenticated user of this call (only valid inside `authenticate` blocks). */
fun ApplicationCall.authUser(): AuthUser =
    principal<AuthUser>() ?: error("No authenticated user on this call - route not under authenticate{}?")

class PendingOAuthGateConfig {
    /**
     * Request-path prefixes the gate skips. Needed because Ktor MERGES path
     * segments: `route("/mcp")` and `route("/mcp/http")` share the `mcp`
     * node, so a plugin meant for the classic SSE subtree also fires for the
     * streamable one - which must stay open (it gates per JSON-RPC method).
     */
    var exemptPrefixes: List<String> = emptyList()
}

/**
 * Refuses callers whose OAuth grant awaits approval ([AuthUser.oauthPending]).
 * Pending bearers still RESOLVE so the MCP handshake on /mcp/http can complete
 * (that endpoint gates per JSON-RPC method instead) - but every DATA surface
 * this plugin is installed on (the REST API, the classic /mcp SSE) answers 403
 * until the user's e-mail link or an admin clears the gate.
 */
val PendingOAuthGate = createRouteScopedPlugin("PendingOAuthGate", ::PendingOAuthGateConfig) {
    val exempt = pluginConfig.exemptPrefixes
    on(AuthenticationChecked) { call ->
        if (exempt.any { call.request.path().startsWith(it) }) return@on
        if (call.principal<AuthUser>()?.oauthPending == true) {
            call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "This AI connection is pending approval - approve it from your e-mail or ask an administrator"),
            )
        }
    }
}

/**
 * A station the caller is entitled to touch: its DB pool plus the caller's
 * grant on it (whose role drives filtering/authorization downstream).
 */
data class StationAccess(val db: StationDb, val grant: StationGrant)

/**
 * The `station` query parameter, checked against the caller's grants but WITHOUT
 * opening that station's DB pool - for endpoints that need to know *whether the
 * caller may act for this station*, not to query it (the report logo is one:
 * spinning up a connection pool to serve a PNG would be absurd).
 *
 * Same failures as [stationAccessOrRespond]: 400 when absent, 403 when ungranted.
 * Responds and returns null in both cases.
 */
suspend fun ApplicationCall.grantedStationIdOrRespond(): String? {
    val stationId = request.queryParameters["station"]
    if (stationId.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Query parameter 'station' is required"))
        return null
    }
    if (authUser().grantFor(stationId) == null) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "No access to station '$stationId'"))
        return null
    }
    return stationId
}

/**
 * Resolves the `station` query parameter against the caller's grants and the
 * hosted stations. Responds and returns null on failure:
 * - 400 when the parameter is missing
 * - 403 when the user has no grant for that station
 * - 404 when the station isn't hosted (not in server.yaml)
 */
suspend fun ApplicationCall.stationAccessOrRespond(registry: StationRegistry): StationAccess? {
    val stationId = request.queryParameters["station"]
    if (stationId.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Query parameter 'station' is required"))
        return null
    }

    val grant = authUser().grantFor(stationId)
    if (grant == null) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "No access to station '$stationId'"))
        return null
    }

    // Pool creation + schema bootstrap are lazy and blocking on first access
    val db = withContext(Dispatchers.IO) { registry.db(stationId) }
    if (db == null) {
        respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown station '$stationId'"))
        return null
    }

    return StationAccess(db, grant)
}

/**
 * Responds 403 and returns false unless the caller is the config-managed
 * super administrator (user management endpoints).
 */
suspend fun ApplicationCall.requireAdmin(): Boolean {
    if (authUser().isAdmin) return true
    respond(
        HttpStatusCode.Forbidden,
        mapOf("error" to "Requires the super administrator")
    )
    return false
}

/**
 * Responds 403 and returns false unless the caller holds [role] on at least
 * one station. For endpoints that aren't station-scoped (e.g. the demo route).
 */
suspend fun ApplicationCall.requireRoleAnywhere(role: UserRole): Boolean {
    if (authUser().hasRoleAnywhere(role)) return true
    respond(
        HttpStatusCode.Forbidden,
        mapOf("error" to "Requires role $role on at least one station")
    )
    return false
}
