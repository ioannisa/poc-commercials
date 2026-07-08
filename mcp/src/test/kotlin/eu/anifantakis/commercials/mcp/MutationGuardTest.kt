package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.server.auth.UserRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The mutation kill switch and the staff-only write guard. */
class MutationGuardTest {

    private val readTools = setOf(
        "list_stations", "search_parties", "party_activity", "party_contracts",
        "contract_spots", "contract_status", "spots_in_break", "station_footprint",
        "generate_break_report",
    )
    private val mutationTools = setOf(
        "add_placement", "delete_placement", "reorder_placements", "send_schedule_email",
    )

    @Test
    fun `mutations disabled hides the write tools`() {
        val services = McpToolServices(registryOf(station("crete-tv")), mutationsEnabled = false)
        val names = buildCommercialsMcpServer(caller(grant("crete-tv")), services).tools.keys
        assertTrue(names.containsAll(readTools))
        assertTrue(mutationTools.none { it in names }, "write tools must be absent when mutations are off")
        assertEquals(readTools.size, names.size)
    }

    @Test
    fun `mutations enabled exposes the write tools`() {
        val services = McpToolServices(registryOf(station("crete-tv")), mutationsEnabled = true)
        val names = buildCommercialsMcpServer(caller(grant("crete-tv")), services).tools.keys
        assertTrue(names.containsAll(readTools + mutationTools))
        assertEquals(readTools.size + mutationTools.size, names.size)
    }

    @Test
    fun `requireStaff blocks non-NORMAL_USER roles`() {
        val services = McpToolServices(registryOf(station("crete-tv")))
        assertFailsWith<McpToolException> { services.requireStaff(grant("crete-tv", UserRole.REPORT_VIEWER)) }
        assertFailsWith<McpToolException> { services.requireStaff(grant("crete-tv", UserRole.CUSTOMER_VIEWER, "A")) }
        services.requireStaff(grant("crete-tv", UserRole.NORMAL_USER)) // no throw
    }
}
