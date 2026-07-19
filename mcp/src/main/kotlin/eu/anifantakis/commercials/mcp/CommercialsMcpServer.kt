package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.mcp.tools.ALL_MCP_TOOLS
import eu.anifantakis.commercials.mcp.tools.ToolContext
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.util.Properties

/**
 * Builds the Commercials Manager MCP [Server]. A fresh server is built per
 * client session (each transport binding calls this), scoped to [caller]'s
 * identity so every tool is grant-checked, and backed by [services].
 *
 * The tool set is data-driven: every entry in [ALL_MCP_TOOLS] is registered,
 * except that mutating tools are dropped unless [McpToolServices.mutationsEnabled]
 * (default-deny). Adding a functionality means adding a `tools/<name>/` folder
 * and listing its object in the registry - this wiring never changes.
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
    val ctx = ToolContext(caller, services)
    ALL_MCP_TOOLS
        .filter { services.mutationsEnabled || !it.mutating }
        .forEach { tool ->
            server.addTool(tool.name, tool.description, tool.inputSchema) { req -> tool.handle(ctx, req) }
        }
    return server
}

internal const val SERVER_NAME: String = "commercials-manager"

/**
 * Advertised to every MCP client in the protocol handshake. Baked from the
 * `server` catalog version by mcp/build.gradle.kts (the MCP server IS part of
 * the server deployment), so it never drifts from what /version reports.
 */
internal val SERVER_VERSION: String by lazy {
    object {}.javaClass.classLoader
        .getResourceAsStream("commercials-mcp-version.properties")
        ?.use { s -> Properties().apply { load(s) }.getProperty("version") }
        ?.takeIf { it.isNotBlank() }
        ?: "0.0.0" // resource missing = a build-script regression, not a runtime error
}
