package eu.anifantakis.commercials.mcp.stdio

import eu.anifantakis.commercials.mcp.McpCaller
import eu.anifantakis.commercials.mcp.McpToolServices
import eu.anifantakis.commercials.mcp.buildCommercialsMcpServer
import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.scheduler.CentralDb
import eu.anifantakis.commercials.server.stations.StationRegistry
import eu.anifantakis.commercials.server.stations.loadHostingConfig
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("commercials-mcp-stdio")

/**
 * MCP stdio entrypoint for clients that spawn a helper process (Claude Desktop,
 * CLI tooling). Boots the SAME persistence/auth stack as the Ktor server -
 * `server.yaml` (or `COMMERCIALS_SERVER`) + the central DB - resolves the caller
 * identity from `COMMERCIALS_MCP_TOKEN` (a real bearer token, so the SAME
 * per-station grant/role checks apply as over HTTP), then serves the tools over
 * stdio.
 *
 * stdout is the MCP protocol channel: logging goes to stderr (see logback.xml)
 * and System.out is redirected to stderr so a stray print cannot corrupt the
 * stream - only the transport writes to the real stdout.
 */
fun main() {
    // Capture the real stdout for the MCP channel, then point System.out at
    // stderr so nothing else can write to the protocol stream by accident.
    val mcpOut = System.out
    System.setOut(System.err)

    val token = System.getenv("COMMERCIALS_MCP_TOKEN")
    if (token.isNullOrBlank()) {
        log.error("COMMERCIALS_MCP_TOKEN is not set - cannot resolve the MCP caller identity. Exiting.")
        exitProcess(1)
    }

    // Boot the persistence/auth stack directly (no Ktor, no Koin), same sources
    // the server uses. Station pools are created lazily on first tool call.
    val hosting = loadHostingConfig()
    val registry = StationRegistry(hosting)
    val centralDb = CentralDb(hosting)
    val authDb = AuthDb(centralDb, registry, hosting)
    authDb.bootstrap() // idempotent: ensures central tables + super-admin sync

    val user = authDb.findUserByToken(token)
    if (user == null) {
        log.error("COMMERCIALS_MCP_TOKEN is not a valid/active token. Exiting.")
        registry.closeAll()
        centralDb.close()
        exitProcess(1)
    }
    log.info("MCP stdio server ready as '{}' ({} station grant(s))", user.username, user.grants.size)

    val services = McpToolServices(registry)
    val server = buildCommercialsMcpServer(McpCaller.of(user), services)

    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        mcpOut.asSink().buffered(),
    ) { /* default builder config */ }

    try {
        runBlocking {
            val session = server.createSession(transport)
            val done = Job()
            session.onClose { done.complete() }
            done.join()
        }
    } finally {
        registry.closeAll()
        centralDb.close()
    }
}
