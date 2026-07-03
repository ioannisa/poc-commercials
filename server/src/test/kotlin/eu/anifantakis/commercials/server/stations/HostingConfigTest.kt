package eu.anifantakis.commercials.server.stations

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HostingConfigTest {

    // Returns Unit (not a generic T): JUnit4 test methods must be void, and
    // several tests are expression-bodied `= withStationsYaml(...) { ... }`.
    private fun withStationsYaml(content: String, block: () -> Unit) {
        val file = File.createTempFile("stations-pool", ".yaml").apply {
            deleteOnExit(); writeText(content)
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
    fun resolutionPrefersOverrideThenGlobalThenBuiltin() {
        assertEquals(3, resolveMaxPoolSize(override = 3, global = 8, builtinDefault = 10))
        assertEquals(8, resolveMaxPoolSize(override = null, global = 8, builtinDefault = 10))
        assertEquals(10, resolveMaxPoolSize(override = null, global = null, builtinDefault = 10))
    }

    @Test
    fun parsesGlobalAndPerConnectionOverrides() = withStationsYaml(
        """
        maxPoolSize: 8
        superAdmin:
          username: root-admin
          password: test-admin-pass
        central:
          jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central"
          username: test
          password: test
          maxPoolSize: 20
        stations:
          - id: crete-tv
            name: "Crete TV"
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_cretetv"
            username: test
            password: test
            maxPoolSize: 3
          - id: radio-984
            name: "Radio 984"
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_radio984"
            username: test
            password: test
        """.trimIndent()
    ) {
        val cfg = loadHostingConfig()
        assertEquals(8, cfg.maxPoolSize)
        assertEquals(20, cfg.central.maxPoolSize)
        // crete-tv overrides, radio-984 inherits the file-wide 8, not the builtin 5
        assertEquals(3, resolveMaxPoolSize(cfg.stations[0].maxPoolSize, cfg.maxPoolSize, DEFAULT_STATION_MAX_POOL))
        assertEquals(8, resolveMaxPoolSize(cfg.stations[1].maxPoolSize, cfg.maxPoolSize, DEFAULT_STATION_MAX_POOL))
    }

    @Test
    fun defaultsApplyWhenPoolSizeOmitted() = withStationsYaml(
        """
        superAdmin:
          username: root-admin
          password: test-admin-pass
        central:
          jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central"
          username: test
          password: test
        stations:
          - id: crete-tv
            name: "Crete TV"
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_cretetv"
            username: test
            password: test
        """.trimIndent()
    ) {
        val cfg = loadHostingConfig()
        assertNull(cfg.maxPoolSize)
        assertNull(cfg.central.maxPoolSize)
        assertEquals(DEFAULT_CENTRAL_MAX_POOL, resolveMaxPoolSize(cfg.central.maxPoolSize, cfg.maxPoolSize, DEFAULT_CENTRAL_MAX_POOL))
        assertEquals(DEFAULT_STATION_MAX_POOL, resolveMaxPoolSize(cfg.stations[0].maxPoolSize, cfg.maxPoolSize, DEFAULT_STATION_MAX_POOL))
    }

    /** The break-glass account is non-negotiable - no superAdmin, no server. */
    @Test
    fun rejectsMissingSuperAdmin() = withStationsYaml(
        """
        central:
          jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central"
          username: test
          password: test
        """.trimIndent()
    ) {
        assertFailsWith<IllegalArgumentException> { loadHostingConfig() }
    }

    @Test
    fun rejectsNonPositivePoolSize() = withStationsYaml(
        """
        superAdmin:
          username: root-admin
          password: test-admin-pass
        central:
          jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central"
          username: test
          password: test
          maxPoolSize: 0
        """.trimIndent()
    ) {
        assertFailsWith<IllegalArgumentException> { loadHostingConfig() }
    }
}
