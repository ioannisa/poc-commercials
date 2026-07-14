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
