package eu.anifantakis.commercials.mcp.stdio.di

import eu.anifantakis.commercials.mcp.di.mcpModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration

/**
 * The stdio entry point's Koin bootstrap: this launcher's own graph
 * ([stdioModule]) plus the tool backend's shared bindings
 * ([mcpModule][eu.anifantakis.commercials.mcp.di.mcpModule]) — the very module
 * the Ktor server also loads, so both transports resolve the identical
 * `McpToolServices`.
 */
fun initKoin(config: KoinAppDeclaration? = null): KoinApplication = startKoin {
    config?.invoke(this)
    modules(stdioModule, mcpModule)
}
