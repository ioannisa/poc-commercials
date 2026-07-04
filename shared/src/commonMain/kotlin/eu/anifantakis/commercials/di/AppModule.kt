package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.admin.AdminApi
import eu.anifantakis.commercials.email.ScheduleEmailApi
import eu.anifantakis.commercials.prefs.UserPreferences
import eu.anifantakis.commercials.admin.MigrationApi
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.data.preferences.createKSafe
import eu.anifantakis.commercials.data.ScheduleRepository
import eu.anifantakis.commercials.db.DbApi
import eu.anifantakis.commercials.finder.SpotFinderApi
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.feature.auth.data.AuthRepositoryImpl
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.presentation.login.LoginViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.koin.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf

/**
 * Shared singletons, one instance for the app lifetime. Platform-specific
 * bindings (ReportService) live in [platformModule]. Classic constructor-
 * reference DSL (kmp-developer di-koin convention); the graph is guarded by
 * KoinGraphTest.
 */
val appModule = module {
    // Factory call, not a constructor - classic lambda definition. The graph
    // checker can't index it, so KSafe consumers mark the parameter @Provided.
    single { createKSafe() }

    singleOf(::AuthSession)

    // App-wide MVI container (kmp-developer global state)
    single { GlobalStateContainer() }

    // :feature:auth - classic DSL (cross-module types; the compiler-plugin
    // checker only indexes this module's typed definitions)
    singleOf(::AuthRepositoryImpl).bind<AuthRepository>()
    viewModelOf(::LoginViewModel)

    singleOf(::UserPreferences)
    singleOf(::ScheduleEmailApi)
    singleOf(::SpotFinderApi)
    singleOf(::AdminApi)
    singleOf(::MigrationApi)
    singleOf(::ScheduleRepository)
    singleOf(::DbApi)
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
