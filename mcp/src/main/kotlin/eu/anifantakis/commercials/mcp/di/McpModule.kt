package eu.anifantakis.commercials.mcp.di

import eu.anifantakis.commercials.mcp.McpToolServices
import eu.anifantakis.commercials.mcp.mcpMutationsEnabled
import org.koin.dsl.module

/**
 * The MCP tool backend's Koin bindings — one file, one module, per the DI
 * conventions.
 *
 * It lives in `:mcp` rather than in a single entry point's `di/` package because
 * BOTH backend entry points need it: the Ktor server (which mounts `/mcp`) and
 * the `:mcp-stdio` launcher. Each entry point supplies the persistence graph
 * ([StationRegistry][eu.anifantakis.commercials.server.stations.StationRegistry]
 * et al.) that these bindings resolve with `get()`; duplicating this binding in
 * both would be exactly the drift the shared modules were extracted to prevent.
 *
 * A lambda (not `singleOf`) because the mutation kill switch is a VALUE read
 * from the environment, not a graph dependency.
 */
val mcpModule = module {
    single<McpToolServices> {
        McpToolServices(registry = get(), mutationsEnabled = mcpMutationsEnabled())
    }
}
