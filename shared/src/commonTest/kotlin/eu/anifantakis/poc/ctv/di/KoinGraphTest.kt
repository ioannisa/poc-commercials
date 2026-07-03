package eu.anifantakis.poc.ctv.di

import eu.anifantakis.poc.ctv.auth.AuthApi
import eu.anifantakis.poc.ctv.auth.AuthSession
import eu.anifantakis.poc.ctv.data.ScheduleRepository
import eu.anifantakis.poc.ctv.db.DbApi
import eu.anifantakis.poc.ctv.reports.ReportService
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
