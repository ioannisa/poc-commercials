package eu.anifantakis.commercials.server.plugins

import eu.anifantakis.commercials.mcp.McpCaller
import eu.anifantakis.commercials.mcp.McpToolServices
import eu.anifantakis.commercials.mcp.buildCommercialsMcpServer
import eu.anifantakis.commercials.server.auth.AuthUser
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.intercept
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.DnsRebindingProtection
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Streamable HTTP MCP transport at `/mcp/http`, hand-wired INSIDE the bearer
 * auth block.
 *
 * The SDK ships `Application.mcpStreamableHttp`, but it opens its own
 * top-level `routing {}` and therefore cannot sit under `authenticate {}` the
 * way the SSE `Route.mcp` does. Its building blocks ARE public, so this file
 * replicates the helper's route body (GET/POST/DELETE + the session-header
 * interceptor) with two deliberate differences:
 *
 * 1. Every route lives under `authenticate(AUTH_BEARER)` - a valid bearer
 *    (PAT or OAuth token) is required on EVERY request, kill switch included.
 * 2. Sessions are bound to the user that initialized them: a session created
 *    by user A answers only to requests whose bearer resolves to user A.
 *    A wrong-user lookup is indistinguishable from an unknown session (404).
 *
 * The `:mcp` module is transport-agnostic; tools and grants behave exactly as
 * they do over the classic SSE endpoint.
 */

/** The SDK's `MCP_SESSION_ID_HEADER` is internal - same value, ours. */
private const val MCP_SESSION_ID = "mcp-session-id"

/** SDK-internal localhost defaults for the DNS-rebinding guard, mirrored. */
private val LOCALHOST_HOSTS = listOf("localhost", "127.0.0.1", "[::1]")
private val LOCALHOST_ORIGINS = listOf("http://localhost", "http://127.0.0.1", "http://[::1]")

/** Idle streamable sessions are evicted after this long (no scheduler thread: swept on each initialize). */
private const val SESSION_IDLE_MILLIS = 30L * 60 * 1000

private val log = LoggerFactory.getLogger("McpStreamable")

/**
 * The live streamable sessions: transport + the user each session was
 * initialized as. Replaces the SDK's internal `TransportManager`, adding the
 * session-to-user binding.
 */
class McpStreamableSessions {
    private class Entry(
        val transport: StreamableHttpServerTransport,
        val userId: Long,
        @Volatile var lastUsedAt: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * The transport for [sessionId] IF it belongs to [userId]; null otherwise.
     * Unknown session and wrong-user are deliberately indistinguishable - both
     * end as a 404 at the call site.
     */
    fun get(sessionId: String, userId: Long): StreamableHttpServerTransport? {
        val entry = entries[sessionId] ?: return null
        if (entry.userId != userId) return null
        entry.lastUsedAt = System.currentTimeMillis()
        return entry.transport
    }

    /**
     * Whether [sessionId] exists at all - NO ownership check, NO idle-clock
     * bump. Only for the GET header-echo interceptor, which runs in the
     * Plugins phase BEFORE authentication (no principal yet). Ownership is
     * enforced in the handlers via [get].
     */
    fun exists(sessionId: String): Boolean = entries.containsKey(sessionId)

    fun put(sessionId: String, transport: StreamableHttpServerTransport, userId: Long) {
        entries[sessionId] = Entry(transport, userId, System.currentTimeMillis())
    }

    fun remove(sessionId: String) {
        entries.remove(sessionId)
    }

    /**
     * Closes and drops sessions idle longer than [SESSION_IDLE_MILLIS]. The SDK
     * never evicts on its own and a streamable session has no connection whose
     * drop would clean it, so this runs on each new initialize.
     */
    suspend fun sweepIdle() {
        val cutoff = System.currentTimeMillis() - SESSION_IDLE_MILLIS
        for ((sessionId, entry) in entries) {
            if (entry.lastUsedAt < cutoff) {
                entries.remove(sessionId)
                // close() fires onSessionClosed -> remove(sessionId): harmless repeat
                runCatching { entry.transport.close() }
                log.info("Evicted idle MCP streamable session {}", sessionId)
            }
        }
    }
}

/** [McpAuthChallenge] configuration: the RFC 9728 resource-metadata URL to advertise. */
class McpAuthChallengeConfig {
    var resourceMetadataUrl: String? = null
}

/**
 * Appends the MCP authorization challenge to 401 responses:
 * `WWW-Authenticate: Bearer resource_metadata="..."`.
 *
 * Native connectors (claude.ai, ChatGPT, ...) key their whole OAuth discovery
 * off this header - and honor it ONLY on an HTTP 401. Ktor's bearer provider
 * has no challenge hook (its 401 is fixed), so this route-scoped plugin
 * decorates the response at the last point before send - the same hook
 * StatusPages uses. The result is a second `WWW-Authenticate` header next to
 * the provider's `Bearer realm=...` - RFC-legal, and MCP clients scan all of
 * them. Installed ONLY on the MCP subtrees so REST 401s stay untouched; a
 * no-op when `publicBaseUrl` is unset (no OAuth to discover).
 */
val McpAuthChallenge = createRouteScopedPlugin("McpAuthChallenge", ::McpAuthChallengeConfig) {
    val url = pluginConfig.resourceMetadataUrl ?: return@createRouteScopedPlugin
    val challenge = "Bearer resource_metadata=\"$url\""
    on(ResponseBodyReadyForSend) { call, content ->
        val status = content.status ?: call.response.status()
        if (status == HttpStatusCode.Unauthorized) {
            call.response.headers.append(HttpHeaders.WWWAuthenticate, challenge)
        }
    }
}

/**
 * Mounts the streamable HTTP endpoint at `path`. Must be called inside
 * `authenticate(AUTH_BEARER)` - handlers assume an authenticated principal.
 */
fun Route.mcpStreamableRoutes(
    path: String,
    services: McpToolServices,
    registry: StationRegistry,
    sessions: McpStreamableSessions,
) {
    route(path) {
        // MCP JSON-RPC needs McpJson (explicitNulls=false, encodeDefaults=true,
        // no class discriminator); the app-global ContentNegotiation uses a
        // different Json. ContentNegotiation is route-scoped in Ktor 3, so this
        // override applies to this subtree only.
        install(ContentNegotiation) { json(McpJson) }

        // Lets the pending-grant gate PEEK at the JSON-RPC method below and
        // still hand the untouched body to the SDK transport.
        install(DoubleReceive)

        install(DnsRebindingProtection) {
            // Mirrors the SDK's secure-by-default: localhost hosts+origins for
            // dev; a remote deployment lists its public host(s) in server.yaml
            // (mcpAllowedHosts) and origin validation is skipped (bearer-authed
            // API - the cookie attack the guard mitigates does not apply).
            val custom = registry.mcpAllowedHosts
            allowedHosts = custom ?: LOCALHOST_HOSTS
            allowedOrigins = if (custom == null) LOCALHOST_ORIGINS else null
        }

        install(McpAuthChallenge) {
            resourceMetadataUrl = registry.publicBaseUrl
                ?.let { "$it/.well-known/oauth-protected-resource$path" }
        }

        // Set Mcp-Session-Id on GET responses before Ktor's sse {} commits
        // headers (replicates the SDK helper, which uses this same deprecated
        // intercept). Runs pre-auth (Plugins phase precedes Authenticate), so
        // it can only check existence - ownership is enforced in the handlers.
        @Suppress("DEPRECATION")
        intercept(ApplicationCallPipeline.Plugins) {
            if (context.request.httpMethod == HttpMethod.Get) {
                val sessionId = context.request.header(MCP_SESSION_ID)
                if (sessionId != null && sessions.exists(sessionId)) {
                    context.response.header(MCP_SESSION_ID, sessionId)
                }
            }
        }

        // Standalone SSE notification stream for an existing session.
        sse {
            val transport = call.resolveOwnedSession(sessions) ?: return@sse
            transport.handleRequest(this, call)
        }

        post {
            // A PENDING grant (awaiting user-e-mail or admin approval) may
            // complete the protocol handshake - otherwise claude.ai reports
            // "Authentication failed" and every retry mints another pending
            // grant - but any data-bearing method answers a JSON-RPC error
            // until the gate clears. Checked per REQUEST, so approval takes
            // effect on the very next call without a new session.
            if (call.authUser().oauthPending && call.refusePendingDataMethod()) return@post

            val sessionId = call.request.header(MCP_SESSION_ID)
            val transport = if (sessionId != null) {
                call.resolveOwnedSession(sessions) ?: return@post
            } else {
                // No session header = the initialize request: new session bound
                // to the authenticated caller, tools registered per their grants.
                sessions.sweepIdle()
                newSessionTransport(call.authUser(), services, sessions)
            }
            transport.handleRequest(null, call)
        }

        delete {
            val transport = call.resolveOwnedSession(sessions) ?: return@delete
            transport.handleRequest(null, call)   // -> handleDeleteRequest -> onSessionClosed -> remove
        }
    }
}

/**
 * Creates the transport + MCP server for a fresh session as [user]. The
 * AuthUser snapshot taken here is the session's identity - exact parity with
 * the SSE endpoint, where the server is also built once per session.
 */
private suspend fun newSessionTransport(
    user: AuthUser,
    services: McpToolServices,
    sessions: McpStreamableSessions,
): StreamableHttpServerTransport {
    // enableJsonResponse is REQUIRED: the POST path has no SSE session
    // (handlePostRequest(session = null, ...)), so responses go out as JSON
    // through the route-scoped ContentNegotiation(McpJson) above.
    val transport = StreamableHttpServerTransport(
        StreamableHttpServerTransport.Configuration(enableJsonResponse = true)
    )
    transport.setOnSessionInitialized { sessionId ->
        sessions.put(sessionId, transport, user.id)
        log.info("MCP streamable session {} initialized for user {}", sessionId, user.username)
    }
    transport.setOnSessionClosed { sessionId ->
        sessions.remove(sessionId)
        log.info("MCP streamable session {} closed", sessionId)
    }

    val server = buildCommercialsMcpServer(McpCaller.of(user), services)
    server.onClose { transport.sessionId?.let { sessions.remove(it) } }
    server.createSession(transport)
    return transport
}

/** The bare protocol handshake a PENDING grant may still perform. */
private val PENDING_ALLOWED_METHODS = setOf("initialize", "ping", "tools/list")

/**
 * When the request is a data-bearing JSON-RPC method, answers it with a
 * "pending approval" error and returns true; the handshake methods (and
 * anything unparseable, which the SDK will reject on its own terms) pass.
 * The body read here is repeatable thanks to the route's [DoubleReceive].
 */
private suspend fun ApplicationCall.refusePendingDataMethod(): Boolean {
    val body = runCatching { receiveText() }.getOrNull() ?: return false
    val request = runCatching { McpJson.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return false
    val method = (request["method"] as? JsonPrimitive)?.contentOrNull ?: return false
    if (method in PENDING_ALLOWED_METHODS || method.startsWith("notifications/")) return false
    respond(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", request["id"] ?: JsonNull)
            putJsonObject("error") {
                put("code", -32002)
                put(
                    "message",
                    "This AI connection is pending approval. Approve it from the e-mail " +
                        "sent to your account, or ask your administrator, then try again.",
                )
            }
        }
    )
    return true
}

/**
 * Resolves the caller's session from the `mcp-session-id` header. Responds and
 * returns null on failure: 400 without the header, 404 for unknown-or-not-yours
 * (deliberately the same answer). Error bodies are JSON-RPC, like the SDK's.
 */
private suspend fun ApplicationCall.resolveOwnedSession(
    sessions: McpStreamableSessions,
): StreamableHttpServerTransport? {
    val sessionId = request.header(MCP_SESSION_ID)
    if (sessionId.isNullOrEmpty()) {
        rejectRpc(HttpStatusCode.BadRequest, "Bad Request: No valid session ID provided")
        return null
    }
    val transport = sessions.get(sessionId, authUser().id)
    if (transport == null) {
        rejectRpc(HttpStatusCode.NotFound, "Session not found")
        return null
    }
    return transport
}

/** The SDK's internal `ApplicationCall.reject`, replicated. */
private suspend fun ApplicationCall.rejectRpc(status: HttpStatusCode, message: String) {
    response.status(status)
    respond(JSONRPCError(id = null, error = RPCError(code = RPCError.ErrorCode.CONNECTION_CLOSED, message = message)))
}
