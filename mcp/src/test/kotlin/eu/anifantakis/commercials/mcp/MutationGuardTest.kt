package eu.anifantakis.commercials.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mutation kill switch: which tools a session even sees. The staff-only write
 * guard (`requireStaff`) is covered in [McpToolServicesTest].
 */
class MutationGuardTest {

    private val readTools = setOf(
        "list_stations", "search_parties", "party_activity", "party_contracts",
        "contract_spots", "contract_status", "list_breaks", "spots_in_break", "station_footprint",
        "generate_break_report",
    )
    private val mutationTools = setOf(
        "add_placement", "delete_placement", "reorder_placements", "send_schedule_email",
    )

    @Test
    fun `mutations disabled hides the write tools`() {
        val services = services(mutationsEnabled = false)
        val names = buildCommercialsMcpServer(caller(grant("crete-tv")), services).tools.keys
        assertTrue(names.containsAll(readTools))
        assertTrue(mutationTools.none { it in names }, "write tools must be absent when mutations are off")
        assertEquals(readTools.size, names.size)
    }

    @Test
    fun `mutations enabled exposes the write tools`() {
        val services = services(mutationsEnabled = true)
        val names = buildCommercialsMcpServer(caller(grant("crete-tv")), services).tools.keys
        assertTrue(names.containsAll(readTools + mutationTools))
        assertEquals(readTools.size + mutationTools.size, names.size)
    }
}
