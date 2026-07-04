package eu.anifantakis.commercials.server.di

import eu.anifantakis.commercials.migration.MigrationService
import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.config.ServerConfig
import eu.anifantakis.commercials.server.config.ServerConfigLoader
import eu.anifantakis.commercials.server.scheduler.CentralDb
import eu.anifantakis.commercials.server.stations.HostingConfig
import eu.anifantakis.commercials.server.stations.StationRegistry
import eu.anifantakis.commercials.server.stations.loadHostingConfig
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

/**
 * Server singletons. Same definition rules as the client (see
 * shared/.../di/PlatformModule.web.kt for the empirical comparison):
 * constructor-wired services use the typed `single<T>()` so the compiler
 * plugin validates the graph; factory-loaded values (ServerConfig from
 * config.properties, HostingConfig from server.yaml) stay classic-DSL
 * and their consumers mark the parameter @Provided.
 */
val serverModule = module {
    single<ServerConfig> { ServerConfigLoader.get() }
    single<HostingConfig> { loadHostingConfig() }

    single<StationRegistry>()
    single<CentralDb>()
    single<AuthDb>()

    single<MigrationService>()
}
