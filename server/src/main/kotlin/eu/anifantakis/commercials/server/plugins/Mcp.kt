package eu.anifantakis.commercials.server.plugins

import eu.anifantakis.commercials.mcp.McpCaller
import eu.anifantakis.commercials.mcp.McpToolServices
import eu.anifantakis.commercials.mcp.buildCommercialsMcpServer
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.describe
import io.ktor.server.sse.*
import io.ktor.utils.io.ExperimentalKtorApi
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import org.koin.ktor.ext.inject

/**
 * Mounts the Model Context Protocol server INSIDE the bearer-auth block, on
 * two transports sharing the one tool core:
 *
 * - `/mcp` - classic SSE (the original mount). Serves `mcp-remote` bridges and
 *   any static-PAT client; unchanged behaviour.
 * - `/mcp/http` - Streamable HTTP (see McpStreamable.kt). The transport native
 *   connectors (claude.ai, ChatGPT, Gemini, ...) speak; works with a PAT
 *   directly or with an OAuth token once `publicBaseUrl` enables the AS.
 *
 * Each MCP session runs as the authenticated user, so every tool is
 * grant-scoped exactly like the REST routes. [buildCommercialsMcpServer] is
 * called per session with the caller identity taken from the bearer principal.
 *
 * DNS-rebinding protection stays on with the SDK's localhost defaults (fine for
 * local/dev clients such as the MCP Inspector). A remote deployment lists its
 * public host(s) in server.yaml (mcpAllowedHosts). Put both endpoints behind
 * TLS - the bearer token travels over the wire.
 *
 * Both subtrees carry [McpAuthChallenge]: when `publicBaseUrl` is set, their
 * 401s advertise the RFC 9728 resource metadata that native connectors need to
 * discover the OAuth server (no-op otherwise).
 */
@OptIn(ExperimentalKtorApi::class)
fun Application.configureMcp() {
    val services by inject<McpToolServices>()
    val registry by inject<StationRegistry>()

    install(SSE)

    routing {
        authenticate(AUTH_BEARER) {
            route("/mcp") {
                // MCP over SSE (the classic transport): JSON-RPC for mcp-remote
                // bridges and static-PAT clients (listed for reference).
                describe { tag("MCP") }
                install(McpAuthChallenge) {
                    resourceMetadataUrl = registry.publicBaseUrl
                        ?.let { "$it/.well-known/oauth-protected-resource/mcp" }
                }
                // Classic SSE has no per-method gate (the SDK owns the whole
                // exchange) - a pending OAuth grant is refused outright here;
                // native connectors negotiate on /mcp/http, which gates per
                // JSON-RPC method instead (and shares this "mcp" path node,
                // hence the exemption).
                install(PendingOAuthGate) { exemptPrefixes = listOf("/mcp/http") }
                // MCP over SSE (classic transport): JSON-RPC for mcp-remote bridges and static-PAT clients.
                mcp(allowedHosts = registry.mcpAllowedHosts) {
                    buildCommercialsMcpServer(McpCaller.of(call.authUser()), services)
                }
            }

            mcpStreamableRoutes("/mcp/http", services, registry, McpStreamableSessions())
        }
    }
}
