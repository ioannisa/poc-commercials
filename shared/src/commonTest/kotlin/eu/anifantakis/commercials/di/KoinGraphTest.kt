package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.auth.AuthApi
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.data.ScheduleRepository
import eu.anifantakis.commercials.db.DbApi
import eu.anifantakis.commercials.reports.ReportService
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Resolves the whole DI graph. This is also the proof that the Koin Compiler
 * Plugin transformed the typed `single<T>()` stubs: untransformed stubs throw
 * "USE_KOIN_COMPILER_PLUGIN" the moment a definition is instantiated.
 */
class KoinGraphTest {

    @Test
    fun fullGraphResolves() {
        val app = koinApplication {
            modules(appModule, platformModule)
        }
        val koin = app.koin

        assertNotNull(koin.get<AuthSession>())
        assertNotNull(koin.get<AuthApi>())
        assertNotNull(koin.get<ScheduleRepository>())
        assertNotNull(koin.get<DbApi>())
        assertNotNull(koin.get<ReportService>())

        app.close()
    }
}
