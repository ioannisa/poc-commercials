package eu.anifantakis.ctv.server.di

import eu.anifantakis.ctv.server.auth.AuthDb
import eu.anifantakis.ctv.server.config.ServerConfig
import eu.anifantakis.ctv.server.config.ServerConfigLoader
import eu.anifantakis.ctv.server.scheduler.SchedulerDb
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

/**
 * Server singletons. Same definition rules as the client (see
 * shared/.../di/PlatformModule.web.kt for the empirical comparison):
 * constructor-wired services use the typed `single<T>()` so the compiler
 * plugin validates the graph; factory-loaded values (ServerConfig comes from
 * config.properties) stay classic-DSL and their consumers mark the
 * parameter @Provided.
 */
val serverModule = module {
    single<ServerConfig> { ServerConfigLoader.get() }

    single<SchedulerDb>()
    single<AuthDb>()
}
