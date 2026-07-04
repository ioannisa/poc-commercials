package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.core.data.party_search.PartySearchRepositoryImpl
import eu.anifantakis.commercials.core.data.preferences.createKSafe
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.domain.party_search.PartySearchRepository
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.feature.auth.data.AuthRepositoryImpl
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.presentation.account_dialogs.ChangePasswordViewModel
import eu.anifantakis.commercials.feature.auth.presentation.account_dialogs.RecoveryCodesViewModel
import eu.anifantakis.commercials.feature.auth.presentation.login.LoginViewModel
import eu.anifantakis.commercials.feature.databases.data.DatabasesRepositoryImpl
import eu.anifantakis.commercials.feature.databases.domain.DatabasesRepository
import eu.anifantakis.commercials.feature.databases.presentation.databases.DatabasesViewModel
import eu.anifantakis.commercials.feature.migration_console.data.MigrationRepositoryImpl
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationRepository
import eu.anifantakis.commercials.feature.migration_console.presentation.migration.MigrationViewModel
import eu.anifantakis.commercials.feature.preferences.data.KSafeUserPreferences
import eu.anifantakis.commercials.feature.preferences.domain.UserPreferences
import eu.anifantakis.commercials.feature.preferences.presentation.preferences.PreferencesViewModel
import eu.anifantakis.commercials.feature.schedule_email.data.ScheduleEmailRepositoryImpl
import eu.anifantakis.commercials.feature.schedule_email.domain.ScheduleEmailRepository
import eu.anifantakis.commercials.feature.schedule_email.presentation.preview.EmailPreviewViewModel
import eu.anifantakis.commercials.feature.schedule_email.presentation.send_dialog.SendScheduleEmailViewModel
import eu.anifantakis.commercials.feature.timetable.data.FinderRepositoryImpl
import eu.anifantakis.commercials.feature.timetable.data.PlacementsRepositoryImpl
import eu.anifantakis.commercials.feature.timetable.data.ScheduleRepositoryImpl
import eu.anifantakis.commercials.feature.timetable.domain.FinderRepository
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.feature.timetable.presentation.commercial_detail.CommercialDetailViewModel
import eu.anifantakis.commercials.feature.timetable.presentation.store.ScheduleCellsStore
import eu.anifantakis.commercials.feature.timetable.presentation.timetable.TimetableViewModel
import eu.anifantakis.commercials.feature.user_management.data.UserManagementRepositoryImpl
import eu.anifantakis.commercials.feature.user_management.domain.UserManagementRepository
import eu.anifantakis.commercials.feature.user_management.presentation.user_management.UserManagementViewModel
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository as TimetableScheduleRepository
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

/*
 * One Koin module per feature (kmp-developer di-koin layout), assembled by
 * [initKoin]. Classic constructor-reference DSL; the graph is guarded by
 * KoinGraphTest. Platform-specific bindings (ReportService) live in
 * [platformModule].
 */

/** App-lifetime infrastructure shared by every feature. */
val coreModule = module {
    // Factory call, not a constructor - classic lambda definition. KSafe
    // consumers mark the parameter @Provided.
    single { createKSafe() }

    singleOf(::AuthSession)

    // App-wide MVI container (kmp-developer global state)
    single { GlobalStateContainer() }

    // Master-data party search (used by timetable finder + schedule email)
    singleOf(::PartySearchRepositoryImpl).bind<PartySearchRepository>()
}

val authModule = module {
    singleOf(::AuthRepositoryImpl).bind<AuthRepository>()
    viewModelOf(::LoginViewModel)
    viewModelOf(::ChangePasswordViewModel)
    viewModelOf(::RecoveryCodesViewModel)
}

val timetableModule = module {
    singleOf(::ScheduleRepositoryImpl).bind<TimetableScheduleRepository>()
    singleOf(::PlacementsRepositoryImpl).bind<PlacementsRepository>()
    singleOf(::FinderRepositoryImpl).bind<FinderRepository>()

    // the ONE shared piece between the grid and the detail screen
    single { ScheduleCellsStore() }

    viewModelOf(::TimetableViewModel)
    viewModel { params ->
        CommercialDetailViewModel(
            breakId = params.get(),
            date = params.get(),
            placementsRepository = get(),
            store = get(),
        )
    }
}

val scheduleEmailModule = module {
    singleOf(::ScheduleEmailRepositoryImpl).bind<ScheduleEmailRepository>()
    viewModelOf(::SendScheduleEmailViewModel)
    viewModel { params -> EmailPreviewViewModel(request = params.get(), repository = get()) }
}

val preferencesModule = module {
    singleOf(::KSafeUserPreferences).bind<UserPreferences>()
    viewModelOf(::PreferencesViewModel)
}

val userManagementModule = module {
    singleOf(::UserManagementRepositoryImpl).bind<UserManagementRepository>()
    viewModelOf(::UserManagementViewModel)
}

val migrationConsoleModule = module {
    singleOf(::MigrationRepositoryImpl).bind<MigrationRepository>()
    viewModelOf(::MigrationViewModel)
}

val databasesModule = module {
    singleOf(::DatabasesRepositoryImpl).bind<DatabasesRepository>()
    viewModelOf(::DatabasesViewModel)
}

/** Per-platform bindings: ReportService (desktop engine / browser API / unsupported). */
expect val platformModule: Module

/** Everything [initKoin] loads - also what KoinGraphTest verifies. */
val allModules: List<Module>
    get() = listOf(
        coreModule,
        authModule,
        timetableModule,
        scheduleEmailModule,
        preferencesModule,
        userManagementModule,
        migrationConsoleModule,
        databasesModule,
        platformModule,
    )

/**
 * Starts Koin with the app's modules. Safe to call more than once (e.g.
 * Android Activity recreation) - subsequent calls are no-ops.
 */
fun initKoin(config: KoinAppDeclaration? = null) {
    if (KoinPlatformTools.defaultContext().getOrNull() != null) return
    startKoin {
        config?.invoke(this)
        modules(allModules)
    }
}
