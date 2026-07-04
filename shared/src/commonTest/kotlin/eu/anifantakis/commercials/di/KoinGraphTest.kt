package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.databases.domain.DatabasesRepository
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationRepository
import eu.anifantakis.commercials.feature.user_management.domain.UserManagementRepository
import eu.anifantakis.commercials.reports.ReportService
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Resolves the whole DI graph - the guard that keeps the classic
 * singleOf/viewModelOf definitions honest (kmp-developer di-koin
 * convention; a missing binding fails here instead of at first use).
 */
class KoinGraphTest {

    @Test
    fun fullGraphResolves() {
        val app = koinApplication {
            modules(allModules)
        }
        val koin = app.koin

        assertNotNull(koin.get<AuthSession>())
        assertNotNull(koin.get<AuthRepository>())
        assertNotNull(koin.get<ScheduleRepository>())
        assertNotNull(koin.get<PlacementsRepository>())
        assertNotNull(koin.get<UserManagementRepository>())
        assertNotNull(koin.get<MigrationRepository>())
        assertNotNull(koin.get<DatabasesRepository>())
        assertNotNull(koin.get<ReportService>())

        app.close()
    }
}
