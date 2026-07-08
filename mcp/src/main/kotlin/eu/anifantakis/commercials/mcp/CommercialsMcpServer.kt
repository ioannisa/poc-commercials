package eu.anifantakis.commercials.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * Builds the Commercials Manager MCP [Server]. A fresh server is built per
 * client session (each transport binding calls this), scoped to the caller's
 * identity so every tool is grant-checked.
 *
 * Phase 0: an empty server that only declares the `tools` capability. The tool
 * registration (read queries, report generation, guarded mutations) is added in
 * later phases.
 */
fun buildCommercialsMcpServer(): Server =
    Server(
        serverInfo = Implementation(name = SERVER_NAME, version = SERVER_VERSION),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = null),
            ),
        ),
    )

internal const val SERVER_NAME: String = "commercials-manager"
internal const val SERVER_VERSION: String = "1.0.0"
