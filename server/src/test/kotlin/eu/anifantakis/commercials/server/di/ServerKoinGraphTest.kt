package eu.anifantakis.commercials.server.di

import eu.anifantakis.commercials.mcp.McpToolServices
import eu.anifantakis.commercials.mcp.di.mcpModule
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
 * Safe without MySQL: pools are only opened on first connection. server.yaml
 * is REQUIRED, so the test provides a minimal one via the `server.config`
 * system property.
 */
class ServerKoinGraphTest {

    private fun withStationsYaml(content: String, block: () -> Unit) {
        val file = File.createTempFile("stations-test", ".yaml").apply {
            deleteOnExit()
            writeText(content)
        }
        System.setProperty("server.config", file.path)
        try {
            block()
        } finally {
            System.clearProperty("server.config")
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
        groups:
          - id: group-a
            name: "Group A"
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_a"
            username: test
            password: test
            stations:
              - id: station-a
                name: "Station A"
              - id: station-b
                name: "Station B"
        """.trimIndent()
    ) {
        // Exactly what Application.module() installs.
        val app = koinApplication {
            modules(serverModule, mcpModule)
        }
        val koin = app.koin

        assertNotNull(koin.get<ServerConfig>())
        assertNotNull(koin.get<CentralDb>())
        assertNotNull(koin.get<AuthDb>())
        // The registry flattens the group's stations: grants and ?station= never
        // learn about groups.
        assertEquals(listOf("station-a", "station-b"), koin.get<StationRegistry>().ids)
        // :mcp's own module resolves against the server's persistence graph
        assertNotNull(koin.get<McpToolServices>())
        // The Galaxy Bridge engine (:galaxy) resolves off the same registry
        assertNotNull(koin.get<eu.anifantakis.commercials.galaxy.GalaxyImportService>())

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
        val app = koinApplication { modules(serverModule, mcpModule) }
        assertEquals(emptyList(), app.koin.get<StationRegistry>().ids)
        app.close()
    }

    /**
     * A group pointing at the central schema must be rejected at load time
     * (query-param differences in the URL must not disguise the collision).
     */
    @Test
    fun groupUsingCentralSchemaIsRejected() = withStationsYaml(
        """
        superAdmin:
          username: root-admin
          password: test-admin-pass
        central:
          jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central?useUnicode=true"
          username: test
          password: test
        groups:
          - id: rogue-group
            name: "Rogue"
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central?characterEncoding=utf8"
            username: test
            password: test
            stations:
              - id: rogue
                name: "Rogue"
        """.trimIndent()
    ) {
        assertFailsWith<IllegalArgumentException> {
            eu.anifantakis.commercials.server.stations.loadHostingConfig()
        }
    }

    /** server.yaml itself is mandatory - no file, no server. */
    @Test
    fun missingStationsFileIsRejected() {
        System.setProperty("server.config", "/nonexistent/server-missing.yaml")
        try {
            assertFailsWith<IllegalArgumentException> {
                eu.anifantakis.commercials.server.stations.loadHostingConfig()
            }
        } finally {
            System.clearProperty("server.config")
        }
    }
}
