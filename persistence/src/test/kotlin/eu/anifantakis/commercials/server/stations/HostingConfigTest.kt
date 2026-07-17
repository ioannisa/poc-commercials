package eu.anifantakis.commercials.server.stations

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostingConfigTest {

    // Returns Unit (not a generic T): JUnit4 test methods must be void, and
    // several tests are expression-bodied `= withStationsYaml(...) { ... }`.
    private fun withStationsYaml(content: String, block: () -> Unit) {
        val file = File.createTempFile("stations-pool", ".yaml").apply {
            deleteOnExit(); writeText(content)
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
    fun resolutionPrefersOverrideThenGlobalThenBuiltin() {
        assertEquals(3, resolveMaxPoolSize(override = 3, global = 8, builtinDefault = 10))
        assertEquals(8, resolveMaxPoolSize(override = null, global = 8, builtinDefault = 10))
        assertEquals(10, resolveMaxPoolSize(override = null, global = null, builtinDefault = 10))
    }

    /**
     * The shape the hosting model is built on: a GROUP owns the database, and
     * its stations live inside it (sharing the customers and contracts stored
     * there once).
     */
    @Test
    fun parsesGroupsWithTheirStations() = withStationsYaml(
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
        groups:
          - id: crete-group
            name: "Crete Group"
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_crete"
            username: test
            password: test
            maxPoolSize: 3
            stations:
              - id: crete-tv
                name: "Crete TV"
              - id: radio-984
                name: "Radio 984"
          - id: channel4-group
            name: "Channel 4"
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_channel4"
            username: test
            password: test
            stations:
              - id: channel-4
                name: "Channel 4"
        """.trimIndent()
    ) {
        val cfg = loadHostingConfig()
        assertEquals(8, cfg.maxPoolSize)
        assertEquals(20, cfg.central.maxPoolSize)
        assertEquals(2, cfg.groups.size)
        // Two stations, ONE database - the whole point.
        assertEquals(listOf("crete-tv", "radio-984"), cfg.groups[0].stations.map { it.id })
        // Flattened across groups: this is what grants and ?station= see.
        assertEquals(listOf("crete-tv", "radio-984", "channel-4"), cfg.stations.map { it.id })
        // The pool is per DATABASE now: crete-group overrides, channel4 inherits
        // the file-wide 8 rather than the built-in.
        assertEquals(3, resolveMaxPoolSize(cfg.groups[0].maxPoolSize, cfg.maxPoolSize, DEFAULT_GROUP_MAX_POOL))
        assertEquals(8, resolveMaxPoolSize(cfg.groups[1].maxPoolSize, cfg.maxPoolSize, DEFAULT_GROUP_MAX_POOL))
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
        groups:
          - id: crete-group
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_crete"
            username: test
            password: test
            stations:
              - id: crete-tv
                name: "Crete TV"
        """.trimIndent()
    ) {
        val cfg = loadHostingConfig()
        assertNull(cfg.maxPoolSize)
        assertNull(cfg.central.maxPoolSize)
        assertEquals(DEFAULT_CENTRAL_MAX_POOL, resolveMaxPoolSize(cfg.central.maxPoolSize, cfg.maxPoolSize, DEFAULT_CENTRAL_MAX_POOL))
        assertEquals(DEFAULT_GROUP_MAX_POOL, resolveMaxPoolSize(cfg.groups[0].maxPoolSize, cfg.maxPoolSize, DEFAULT_GROUP_MAX_POOL))
    }

    /**
     * The Swagger UI toggle: OFF unless server.yaml opts in, so no deployment
     * exposes the API surface at /swagger by accident.
     */
    @Test
    fun swaggerDefaultsOffAndParsesWhenSet() {
        val base = """
            superAdmin:
              username: root-admin
              password: test-admin-pass
            central:
              jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central"
              username: test
              password: test
            groups:
              - id: crete-group
                jdbcUrl: "jdbc:mysql://localhost:3306/commercials_crete"
                username: test
                password: test
                stations:
                  - id: crete-tv
                    name: "Crete TV"
        """.trimIndent()

        withStationsYaml(base) { assertEquals(false, loadHostingConfig().swagger) }
        withStationsYaml("swagger: true\n$base") { assertTrue(loadHostingConfig().swagger) }
    }

    /**
     * A station id is the key of a user's grant and the value of `?station=`, so
     * it must be unique across the WHOLE file - not merely inside its group.
     */
    @Test
    fun rejectsAStationIdReusedInAnotherGroup() = withStationsYaml(
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
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_a"
            username: test
            password: test
            stations:
              - id: crete-tv
                name: "Crete TV"
          - id: group-b
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_b"
            username: test
            password: test
            stations:
              - id: crete-tv
                name: "Crete TV (again)"
        """.trimIndent()
    ) {
        val e = assertFailsWith<IllegalArgumentException> { loadHostingConfig() }
        assertTrue(e.message!!.contains("crete-tv"), "the clashing id is named: ${e.message}")
    }

    /** Two groups on one database would silently merge their customers. */
    @Test
    fun rejectsTwoGroupsSharingOneDatabase() = withStationsYaml(
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
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_shared"
            username: test
            password: test
            stations:
              - id: station-a
                name: "A"
          - id: group-b
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_shared?useSSL=false"
            username: test
            password: test
            stations:
              - id: station-b
                name: "B"
        """.trimIndent()
    ) {
        assertFailsWith<IllegalArgumentException> { loadHostingConfig() }
    }

    /** Central is standalone: it holds users and grants, never station data. */
    @Test
    fun rejectsAGroupPointedAtTheCentralDatabase() = withStationsYaml(
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
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central?useSSL=false"
            username: test
            password: test
            stations:
              - id: station-a
                name: "A"
        """.trimIndent()
    ) {
        assertFailsWith<IllegalArgumentException> { loadHostingConfig() }
    }

    /**
     * The old flat layout gave every station its own database, which duplicated
     * the group's customers and split its contracts. Fail with the recipe rather
     * than with a kaml "unknown property" further down.
     */
    @Test
    fun rejectsTheOldFlatStationsListWithAnExplanation() = withStationsYaml(
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
        val e = assertFailsWith<IllegalArgumentException> { loadHostingConfig() }
        assertTrue(e.message!!.contains("groups:"), "it says how to convert: ${e.message}")
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

    // ─────────────────────────────────────── publicBaseUrl (OAuth issuer) ──

    @Test
    fun publicBaseUrlDefaultsToNullWhichKeepsOAuthOff() = withStationsYaml(
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
        val cfg = loadHostingConfig()
        assertNull(cfg.publicBaseUrl)
        assertEquals(false, cfg.behindReverseProxy)
        assertNull(StationRegistry(cfg).publicBaseUrl)
    }

    /** The registry exposure is the issuer - a trailing slash would leak into every derived endpoint URL. */
    @Test
    fun publicBaseUrlIsTrimmedOfTrailingSlash() = withStationsYaml(
        """
        superAdmin:
          username: root-admin
          password: test-admin-pass
        central:
          jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central"
          username: test
          password: test
        publicBaseUrl: "https://mcp.example.gr/"
        behindReverseProxy: true
        """.trimIndent()
    ) {
        val cfg = loadHostingConfig()
        val registry = StationRegistry(cfg)
        assertEquals("https://mcp.example.gr", registry.publicBaseUrl)
        assertTrue(registry.behindReverseProxy)
    }

    @Test
    fun rejectsPublicBaseUrlWithoutHttpScheme() = withStationsYaml(
        """
        superAdmin:
          username: root-admin
          password: test-admin-pass
        central:
          jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central"
          username: test
          password: test
        publicBaseUrl: "mcp.example.gr"
        """.trimIndent()
    ) {
        assertFailsWith<IllegalArgumentException> { loadHostingConfig() }
    }
}
