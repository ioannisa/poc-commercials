package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.data.ScheduleRepository
import eu.anifantakis.commercials.db.DbApi
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
            modules(appModule, platformModule)
        }
        val koin = app.koin

        assertNotNull(koin.get<AuthSession>())
        assertNotNull(koin.get<AuthRepository>())
        assertNotNull(koin.get<ScheduleRepository>())
        assertNotNull(koin.get<DbApi>())
        assertNotNull(koin.get<ReportService>())

        app.close()
    }
}
