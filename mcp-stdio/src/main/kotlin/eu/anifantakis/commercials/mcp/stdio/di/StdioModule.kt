package eu.anifantakis.commercials.mcp.stdio.di

import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.scheduler.CentralDb
import eu.anifantakis.commercials.server.stations.HostingConfig
import eu.anifantakis.commercials.server.stations.StationRegistry
import eu.anifantakis.commercials.server.stations.loadHostingConfig
import org.koin.dsl.module

/**
 * This entry point's persistence/auth graph — the stdio launcher's counterpart
 * of the Ktor server's `serverModule`. It boots the SAME sources (`server.yaml`
 * via `COMMERCIALS_SERVER`, the central DB), just without Ktor.
 *
 * Explicit lambdas rather than the constructor-reference `single<T>()` form:
 * that form comes from the Koin compiler plugin, which this module does not apply.
 */
val stdioModule = module {
    single<HostingConfig> { loadHostingConfig() }
    single<StationRegistry> { StationRegistry(get()) }
    single<CentralDb> { CentralDb(get()) }
    single<AuthDb> { AuthDb(get(), get(), get()) }
}
