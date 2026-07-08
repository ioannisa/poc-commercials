package eu.anifantakis.commercials.mcp.stdio

import eu.anifantakis.commercials.mcp.buildCommercialsMcpServer

/**
 * MCP stdio entrypoint.
 *
 * Phase 0 placeholder: builds the server to prove the module graph and the MCP
 * SDK resolve. Phase 4 wires the real [io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport],
 * the Koin boot of the persistence/auth stack, and the COMMERCIALS_MCP_TOKEN
 * caller identity.
 *
 * stdout is reserved for the MCP protocol channel - diagnostics go to stderr.
 */
fun main() {
    buildCommercialsMcpServer()
    System.err.println("commercials-manager MCP server built (stdio transport wired in Phase 4)")
}
