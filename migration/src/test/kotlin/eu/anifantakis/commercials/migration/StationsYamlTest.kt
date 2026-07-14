package eu.anifantakis.commercials.migration

import eu.anifantakis.commercials.server.stations.loadHostingConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * server.yaml is edited as TEXT so the operator's comments survive, and it is
 * now two levels deep (a group owns the database, its stations sit inside it).
 * These tests pin both halves of that: the edit lands in the right block, and
 * the file still parses afterwards.
 */
class StationsYamlTest {

    private val base = """
        # The operator's own notes - these MUST survive every edit.
        superAdmin:
          username: su
          password: 1234

        central:
          jdbcUrl: "jdbc:mysql://localhost:3306/commercials_central"
          username: root
          password: rootpass123

        groups:
          # The Crete company: a TV channel and a radio station over ONE database.
          - id: crete-group
            name: "Crete Group"
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_crete"
            username: root
            password: rootpass123
            stations:
              - id: crete-tv
                name: "Crete TV"

          - id: channel4-group
            name: "Channel 4"
            jdbcUrl: "jdbc:mysql://localhost:3306/commercials_channel4"
            username: root
            password: rootpass123
            stations:
              - id: channel-4
                name: "Channel 4"
    """.trimIndent() + "\n"

    private fun yaml(content: String = base): File =
        File.createTempFile("server-yaml-test", ".yaml").apply { deleteOnExit(); writeText(content) }

    /** Parse the edited file exactly as the server will at its next boot. */
    private fun <T> File.reloaded(block: (eu.anifantakis.commercials.server.stations.HostingConfig) -> T): T {
        System.setProperty("server.config", path)
        try {
            return block(loadHostingConfig())
        } finally {
            System.clearProperty("server.config")
        }
    }

    @Test
    fun appendsAStationInsideItsGroupAndKeepsTheComments() {
        val file = yaml()

        appendStationToGroup(file, groupId = "crete-group", id = "radio-984", name = "Radio 984")

        file.reloaded { cfg ->
            val crete = cfg.groups.first { it.id == "crete-group" }
            assertEquals(listOf("crete-tv", "radio-984"), crete.stations.map { it.id })
            // It joined the GROUP, not the file: the sibling group is untouched.
            assertEquals(listOf("channel-4"), cfg.groups.first { it.id == "channel4-group" }.stations.map { it.id })
        }
        assertTrue(file.readText().contains("# The operator's own notes"), "comments survive the surgery")
        assertTrue(file.readText().contains("# The Crete company"), "in-block comments survive too")
    }

    @Test
    fun appendsAWholeGroupWithItsStations() {
        val file = yaml()

        appendGroupToYaml(
            file = file,
            id = "athens-group",
            name = "Athens Group",
            jdbcUrl = "jdbc:mysql://localhost:3306/commercials_athens",
            username = "root",
            password = "rootpass123",
            stations = listOf(
                Triple("athens-tv", "Athens TV", "/logos/athens.png"),
                Triple("athens-fm", "Athens FM", null),
            ),
        )

        file.reloaded { cfg ->
            val athens = cfg.groups.first { it.id == "athens-group" }
            assertEquals("jdbc:mysql://localhost:3306/commercials_athens", athens.jdbcUrl)
            assertEquals(listOf("athens-tv", "athens-fm"), athens.stations.map { it.id })
            assertEquals("/logos/athens.png", athens.stations[0].logo)
            assertEquals(3, cfg.groups.size, "the existing groups are still there")
        }
    }

    @Test
    fun removesAStationFromItsGroupWithoutTouchingTheOthers() {
        val file = yaml()
        appendStationToGroup(file, groupId = "crete-group", id = "radio-984", name = "Radio 984")

        assertTrue(removeStationFromYaml(file, "radio-984"))

        file.reloaded { cfg ->
            assertEquals(listOf("crete-tv"), cfg.groups.first { it.id == "crete-group" }.stations.map { it.id })
            assertEquals(listOf("crete-tv", "channel-4"), cfg.stations.map { it.id }, "only the one station went")
        }
    }

    /**
     * Removing a group's LAST station must take the now-empty `stations:` key
     * with it: a key with nothing under it is YAML null, not an empty list, and
     * the server would refuse to boot on it.
     */
    @Test
    fun removingTheLastStationOfAGroupLeavesAFileTheServerCanStillRead() {
        val file = yaml()

        assertTrue(removeStationFromYaml(file, "crete-tv"))

        file.reloaded { cfg ->
            val crete = cfg.groups.first { it.id == "crete-group" }
            assertTrue(crete.stations.isEmpty(), "the group survives, with no stations")
            assertEquals(listOf("channel-4"), cfg.stations.map { it.id })
        }
    }

    @Test
    fun removesAWholeGroupAndAllItsStations() {
        val file = yaml()

        assertTrue(removeGroupFromYaml(file, "crete-group"))

        file.reloaded { cfg ->
            assertEquals(listOf("channel4-group"), cfg.groups.map { it.id })
            assertFalse(cfg.stations.any { it.id == "crete-tv" }, "its stations went with it")
        }
    }

    /**
     * The indent-anchored match matters: a GROUP whose id happens to equal a
     * station id must not be eaten by a station removal (the old flat scanner,
     * which matched any `- id:` line, would have).
     */
    @Test
    fun removingAStationDoesNotEatAGroupOfTheSameName() {
        // "channel-4" is now BOTH a group id and a station id inside it. The
        // match is indent-anchored, so only the STATION line can be hit - the
        // old flat scanner matched any `- id:` line and would have eaten the group.
        val file = yaml(base.replace("- id: channel4-group", "- id: channel-4"))

        assertTrue(removeStationFromYaml(file, "channel-4"))

        file.reloaded { cfg ->
            assertTrue(cfg.groups.any { it.id == "channel-4" }, "the GROUP survived; only its station went")
            assertFalse(cfg.stations.any { it.id == "channel-4" })
        }
    }

    @Test
    fun refusesADuplicateStationIdAcrossGroups() {
        val file = yaml()

        // Station ids are the grant key - unique across the whole file.
        assertFailsWith<IllegalArgumentException> {
            appendStationToGroup(file, groupId = "crete-group", id = "channel-4", name = "Clash")
        }
        file.reloaded { cfg ->
            assertEquals(listOf("crete-tv"), cfg.groups.first { it.id == "crete-group" }.stations.map { it.id })
        }
    }

    @Test
    fun refusesADuplicateGroupId() {
        val file = yaml()

        assertFailsWith<IllegalArgumentException> {
            appendGroupToYaml(
                file, id = "crete-group", name = "Clash",
                jdbcUrl = "jdbc:mysql://localhost:3306/x", username = "root", password = "p",
                stations = listOf(Triple("new-station", "New", null)),
            )
        }
    }

    /**
     * The safety net: text surgery on a file a human also edits can silently
     * produce something unreadable. It must fail BEFORE writing, not at the
     * server's next boot.
     */
    @Test
    fun refusesToWriteAFileTheServerCouldNotRead() {
        val broken = yaml(base.replace("groups:", "groups: [oops"))
        val before = broken.readText()

        assertFailsWith<Exception> {
            appendStationToGroup(broken, groupId = "crete-group", id = "radio-984", name = "Radio 984")
        }
        assertEquals(before, broken.readText(), "the file was NOT modified")
    }
}
