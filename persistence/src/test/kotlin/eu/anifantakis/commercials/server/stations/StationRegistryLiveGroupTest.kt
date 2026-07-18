package eu.anifantakis.commercials.server.stations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A group migrated in at RUNTIME must be hosted immediately - no server restart.
 *
 * This is the server half of "the new database does not appear in my dropdown".
 * The other half was the client caching its station list from the login reply
 * forever; the keep-alive now re-reads it. But that refresh is only worth
 * anything if the server's answer is itself live, and these accessors are what
 * it is computed from:
 *
 *   - `ids`      - the super admin's grants are synthesized from it on EVERY
 *                  principal load (AuthDb.toAuthUser), so a new station is
 *                  granted to them the moment it is hosted.
 *   - `config()` - turns each grant into the station name the dropdown shows;
 *                  a grant whose station it cannot resolve is dropped.
 *
 * Both are computed getters over a CopyOnWriteArrayList, NOT snapshots taken at
 * boot. If either is ever "optimized" into a cached val, a freshly migrated group
 * goes back to being invisible until the process is restarted - and the symptom
 * would show up in the UI, three layers away from the cause.
 */
class StationRegistryLiveGroupTest {

    private fun db(url: String) = DbConnectionConfig(
        jdbcUrl = url,
        username = "root",
        password = "secret",
    )

    private fun registry(vararg groups: GroupConfig) = StationRegistry(
        HostingConfig(central = db("jdbc:mysql://localhost:3306/central"), groups = groups.toList())
    )

    private fun group(id: String, vararg stationIds: String) = GroupConfig(
        id = id,
        name = id,
        jdbcUrl = "jdbc:mysql://localhost:3306/$id",
        username = "root",
        password = "secret",
        stations = stationIds.map { StationConfig(id = it, name = it.uppercase()) },
    )

    private fun namedGroup(id: String, name: String, vararg stationIds: String) =
        group(id, *stationIds).copy(name = name)

    /**
     * Account emails must be branded with the groups the user was PLACED IN
     * (their grants), not every group the server hosts - the operator's fix.
     */
    @Test
    fun brandNameForScopesToTheStationsGroups() {
        val registry = registry(
            namedGroup("crete-group", "Κρητική Ραδιοτηλεόραση", "crete-tv", "radio-984"),
            namedGroup("ch4-group", "Channel 4", "channel-4"),
            namedGroup("test-group", "Test Group", "test-tv"),
        )

        // Placed only on Crete stations -> only that group's name.
        assertEquals("Κρητική Ραδιοτηλεόραση", registry.brandNameFor(listOf("crete-tv", "radio-984")))
        // Placed across two groups -> both, distinct, in first-seen order.
        assertEquals("Κρητική Ραδιοτηλεόραση · Test Group", registry.brandNameFor(listOf("crete-tv", "test-tv")))
        // No grants -> falls back to the whole-installation brand (every group).
        assertEquals(registry.brandName, registry.brandNameFor(emptyList()))
        assertEquals("Κρητική Ραδιοτηλεόραση · Channel 4 · Test Group", registry.brandName)
    }

    /** The exact sequence a migration performs: boot with one group, host another. */
    @Test
    fun aGroupAddedAtRuntimeIsHostedWithoutARestart() {
        val registry = registry(group("crete-group", "crete-tv", "radio-984"))
        assertEquals(listOf("crete-tv", "radio-984"), registry.ids)

        registry.addGroup(group("test-omilos", "test-tv", "test-radio"))

        assertEquals(
            listOf("crete-tv", "radio-984", "test-tv", "test-radio"),
            registry.ids,
            "ids feeds the super admin's grants on every request - a new station must be in it at once",
        )
        assertNotNull(
            registry.config("test-tv"),
            "config() resolves a grant into the name the dropdown renders",
        )
        assertEquals("TEST-TV", registry.config("test-tv")?.name)
    }

    /**
     * The id is the key in user grants and in every API call, so a duplicate would
     * silently route one station's spots to another's database. Refuse the whole
     * group rather than host half of it.
     */
    @Test
    fun aGroupReusingAHostedStationIdIsRefused() {
        val registry = registry(group("crete-group", "crete-tv"))

        val error = assertFailsWith<IllegalArgumentException> {
            registry.addGroup(group("other-group", "crete-tv"))
        }

        assertTrue("crete-tv" in error.message.orEmpty())
        assertEquals(listOf("crete-tv"), registry.ids, "the refused group must leave nothing behind")
    }
}
