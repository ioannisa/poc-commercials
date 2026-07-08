package eu.anifantakis.commercials.server.plugins

import eu.anifantakis.commercials.mcp.McpCaller
import eu.anifantakis.commercials.mcp.McpToolServices
import eu.anifantakis.commercials.mcp.buildCommercialsMcpServer
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import org.koin.ktor.ext.inject

/**
 * Mounts the Model Context Protocol server at `/mcp` over SSE, INSIDE the
 * bearer-auth block: each MCP session runs as the authenticated user, so every
 * tool is grant-scoped exactly like the REST routes. [buildCommercialsMcpServer]
 * is called per session with the caller identity taken from the SSE call's
 * bearer principal.
 *
 * DNS-rebinding protection stays on with the SDK's localhost defaults (fine for
 * local/dev clients such as the MCP Inspector). A remote deployment should pass
 * its public host via `allowedHosts` - or disable it, since this API is
 * bearer-authenticated and the cookie-based attack it mitigates does not apply.
 */
fun Application.configureMcp() {
    val services by inject<McpToolServices>()

    install(SSE)

    routing {
        authenticate(AUTH_BEARER) {
            mcp(path = "/mcp") {
                buildCommercialsMcpServer(McpCaller.of(call.authUser()), services)
            }
        }
    }
}
