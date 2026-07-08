package eu.anifantakis.commercials.mcp.stdio

/**
 * MCP stdio entrypoint.
 *
 * Placeholder until Phase 4, which wires the real
 * [io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport], the Koin
 * boot of the persistence/auth stack, and the COMMERCIALS_MCP_TOKEN caller
 * identity, then serves `buildCommercialsMcpServer(caller, services)` over stdio.
 *
 * stdout is reserved for the MCP protocol channel - diagnostics go to stderr.
 */
fun main() {
    System.err.println("commercials-manager MCP stdio server - transport wiring lands in Phase 4")
}
