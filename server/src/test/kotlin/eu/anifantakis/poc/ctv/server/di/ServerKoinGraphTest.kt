package eu.anifantakis.poc.ctv.server.di

import eu.anifantakis.poc.ctv.server.auth.AuthDb
import eu.anifantakis.poc.ctv.server.config.ServerConfig
import eu.anifantakis.poc.ctv.server.scheduler.SchedulerDb
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Resolves the whole server DI graph. This is the server's graph safety net:
 * unlike the client (whose statically visible `startKoin` lets the compiler
 * plugin validate the graph at build time), koin-ktor's `install(Koin)`
 * starts Koin at runtime where the checker can't see it - so a missing
 * definition here must be caught by this test instead.
 *
 * Safe without MySQL: SchedulerDb's connection pool is lazy, and ServerConfig
 * falls back to defaults when config.properties is absent.
 */
class ServerKoinGraphTest {

    @Test
    fun fullGraphResolves() {
        val app = koinApplication {
            modules(serverModule)
        }
        val koin = app.koin

        assertNotNull(koin.get<ServerConfig>())
        assertNotNull(koin.get<SchedulerDb>())
        assertNotNull(koin.get<AuthDb>())

        app.close()
    }
}
