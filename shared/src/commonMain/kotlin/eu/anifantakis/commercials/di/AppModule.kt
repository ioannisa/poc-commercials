package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.data.preferences.createKSafe
import eu.anifantakis.commercials.core.data.party_search.PartySearchRepositoryImpl
import eu.anifantakis.commercials.core.domain.party_search.PartySearchRepository
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.feature.auth.data.AuthRepositoryImpl
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.presentation.account_dialogs.ChangePasswordViewModel
import eu.anifantakis.commercials.feature.databases.data.DatabasesRepositoryImpl
import eu.anifantakis.commercials.feature.databases.domain.DatabasesRepository
import eu.anifantakis.commercials.feature.databases.presentation.databases.DatabasesViewModel
import eu.anifantakis.commercials.feature.migration_console.data.MigrationRepositoryImpl
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationRepository
import eu.anifantakis.commercials.feature.migration_console.presentation.migration.MigrationViewModel
import eu.anifantakis.commercials.feature.user_management.data.UserManagementRepositoryImpl
import eu.anifantakis.commercials.feature.user_management.domain.UserManagementRepository
import eu.anifantakis.commercials.feature.user_management.presentation.user_management.UserManagementViewModel
import eu.anifantakis.commercials.feature.auth.presentation.account_dialogs.RecoveryCodesViewModel
import eu.anifantakis.commercials.feature.auth.presentation.login.LoginViewModel
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
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository as TimetableScheduleRepository
import eu.anifantakis.commercials.feature.timetable.presentation.commercial_detail.CommercialDetailViewModel
import eu.anifantakis.commercials.feature.timetable.presentation.store.ScheduleCellsStore
import eu.anifantakis.commercials.feature.timetable.presentation.timetable.TimetableViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.koin.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
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
    singleOf(::PartySearchRepositoryImpl).bind<PartySearchRepository>()
    singleOf(::AuthRepositoryImpl).bind<AuthRepository>()

    // :feature:timetable
    singleOf(::ScheduleRepositoryImpl).bind<TimetableScheduleRepository>()
    singleOf(::PlacementsRepositoryImpl).bind<PlacementsRepository>()
    singleOf(::FinderRepositoryImpl).bind<FinderRepository>()
    single { ScheduleCellsStore() }
    viewModelOf(::TimetableViewModel)
    // :feature:schedule-email
    singleOf(::ScheduleEmailRepositoryImpl).bind<ScheduleEmailRepository>()
    viewModelOf(::SendScheduleEmailViewModel)
    viewModel { params -> EmailPreviewViewModel(request = params.get(), repository = get()) }

    viewModel { params ->
        CommercialDetailViewModel(
            breakId = params.get(),
            date = params.get(),
            placementsRepository = get(),
            store = get(),
        )
    }
    viewModelOf(::LoginViewModel)

    singleOf(::KSafeUserPreferences).bind<UserPreferences>()
    viewModelOf(::PreferencesViewModel)

    // super-admin features
    singleOf(::UserManagementRepositoryImpl).bind<UserManagementRepository>()
    viewModelOf(::UserManagementViewModel)
    singleOf(::MigrationRepositoryImpl).bind<MigrationRepository>()
    viewModelOf(::MigrationViewModel)
    singleOf(::DatabasesRepositoryImpl).bind<DatabasesRepository>()
    viewModelOf(::DatabasesViewModel)
    viewModelOf(::ChangePasswordViewModel)
    viewModelOf(::RecoveryCodesViewModel)
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
