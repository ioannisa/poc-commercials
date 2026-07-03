package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.admin.AdminApi
import eu.anifantakis.commercials.auth.AuthApi
import eu.anifantakis.commercials.admin.MigrationApi
import eu.anifantakis.commercials.auth.AuthSession
import eu.anifantakis.commercials.auth.createKSafe
import eu.anifantakis.commercials.data.ScheduleRepository
import eu.anifantakis.commercials.db.DbApi
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.koin.plugin.module.dsl.single

/**
 * Shared singletons, one instance for the app lifetime. Platform-specific
 * bindings (ReportService) live in [platformModule].
 *
 * Definitions use the Koin Compiler Plugin's typed DSL (`single<T>()`): the
 * plugin generates the constructor wiring at compile time and validates the
 * graph - a missing dependency is a build error, not a runtime crash.
 */
val appModule = module {
    // Factory call, not a constructor - classic lambda definition. The graph
    // checker can't index it, so KSafe consumers mark the parameter @Provided.
    single { createKSafe() }

    single<AuthSession>()
    single<AuthApi>()
    single<AdminApi>()
    single<MigrationApi>()
    single<ScheduleRepository>()
    single<DbApi>()
}

/** Per-platform bindings: ReportService (desktop engine / browser API / unsupported). */
expect val platformModule: Module

/**
 * Starts Koin with the app's modules. Safe to call more than once (e.g.
 * Android Activity recreation) - subsequent calls are no-ops.
 */
fun initKoin(config: KoinAppDeclaration? = null) {
    if (KoinPlatformTools.defaultContext().getOrNull() != null) return
    startKoin {
        config?.invoke(this)
        modules(appModule, platformModule)
    }
}
