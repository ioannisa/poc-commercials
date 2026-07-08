package eu.anifantakis.commercials.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * Builds the Commercials Manager MCP [Server]. A fresh server is built per
 * client session (each transport binding calls this), scoped to [caller]'s
 * identity so every tool is grant-checked, and backed by [services].
 *
 * Registers the read query tools today; report generation and guarded mutations
 * are added in later phases.
 */
fun buildCommercialsMcpServer(caller: McpCaller, services: McpToolServices): Server {
    val server = Server(
        serverInfo = Implementation(name = SERVER_NAME, version = SERVER_VERSION),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = null),
            ),
        ),
    )
    server.registerReadTools(caller, services)
    server.registerReportTools(caller, services)
    return server
}

internal const val SERVER_NAME: String = "commercials-manager"
internal const val SERVER_VERSION: String = "1.0.0"
