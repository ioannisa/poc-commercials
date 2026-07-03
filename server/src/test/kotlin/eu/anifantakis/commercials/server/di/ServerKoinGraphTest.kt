package eu.anifantakis.commercials.server.di

import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.config.ServerConfig
import eu.anifantakis.commercials.server.scheduler.CentralDb
import eu.anifantakis.commercials.server.stations.StationRegistry
import org.koin.dsl.koinApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Resolves the whole server DI graph. This is the server's graph safety net:
 * unlike the client (whose statically visible `startKoin` lets the compiler
 * plugin validate the graph at build time), koin-ktor's `install(Koin)`
 * starts Koin at runtime where the checker can't see it - so a missing
 * definition here must be caught by this test instead.
 *
 * Safe without MySQL: pools are only opened on first connection. stations.yaml
 * is REQUIRED, so the test provides a minimal one via the `stations.config`
 * system property.
 */
class ServerKoinGraphTest {

    private fun withStationsYaml(content: String, block: () -> Unit) {
        val file = File.createTempFile("stations-test", ".yaml").apply {
            deleteOnExit()
            writeText(content)
        }
        System.setProperty("stations.config", file.path)
        try {
            block()
        } finally {
            System.clearProperty("stations.config")
            file.delete()
        }
    }

    @Test
    fun fullGraphResolves() = withStationsYaml(
        """
        superAdmin:
          username: root-admin
          password: test-admin-pass
        central:
          jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central"
          username: test
          password: test
        stations:
          - id: station-a
            name: "Station A"
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_a"
            username: test
            password: test
        """.trimIndent()
    ) {
        val app = koinApplication {
            modules(serverModule)
        }
        val koin = app.koin

        assertNotNull(koin.get<ServerConfig>())
        assertNotNull(koin.get<CentralDb>())
        assertNotNull(koin.get<AuthDb>())
        assertEquals(listOf("station-a"), koin.get<StationRegistry>().ids)

        app.close()
    }

    /** Zero stations is a legal layout - central alone must still resolve. */
    @Test
    fun graphResolvesWithNoStations() = withStationsYaml(
        """
        superAdmin:
          username: root-admin
          password: test-admin-pass
        central:
          jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central"
          username: test
          password: test
        """.trimIndent()
    ) {
        val app = koinApplication { modules(serverModule) }
        assertEquals(emptyList(), app.koin.get<StationRegistry>().ids)
        app.close()
    }

    /**
     * A station pointing at the central schema must be rejected at load time
     * (query-param differences in the URL must not disguise the collision).
     */
    @Test
    fun stationUsingCentralSchemaIsRejected() = withStationsYaml(
        """
        superAdmin:
          username: root-admin
          password: test-admin-pass
        central:
          jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central?useUnicode=true"
          username: test
          password: test
        stations:
          - id: rogue
            name: "Rogue"
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central?characterEncoding=utf8"
            username: test
            password: test
        """.trimIndent()
    ) {
        assertFailsWith<IllegalArgumentException> {
            eu.anifantakis.commercials.server.stations.loadHostingConfig()
        }
    }

    /** stations.yaml itself is mandatory - no file, no server. */
    @Test
    fun missingStationsFileIsRejected() {
        System.setProperty("stations.config", "/nonexistent/stations-missing.yaml")
        try {
            assertFailsWith<IllegalArgumentException> {
                eu.anifantakis.commercials.server.stations.loadHostingConfig()
            }
        } finally {
            System.clearProperty("stations.config")
        }
    }
}
