package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.scheduler.BreakZone
import eu.anifantakis.commercials.server.scheduler.BreakSlotRow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Station resolution, authorization and break scoping — now driven entirely by
 * fakes behind the ports, so no MySQL is involved.
 */
class McpToolServicesTest {

    private val break1730 = BreakSlotRow(id = 7, hour = 17, minute = 30, label = "17:30", zone = BreakZone.PRIME)
    private val date = LocalDate.of(2026, 7, 3)

    /** crete-tv: one break at 17:30 holding a spot of AAA and a spot of BBB. */
    private fun creteTv() = FakeStationDataSource(
        breaks = listOf(break1730),
        byKey = mapOf((7L to date) to listOf(commercial("AAA", "SPOT A"), commercial("BBB", "SPOT B", spotId = 2))),
    )

    private fun directory(vararg extraIds: String) = FakeStationDirectory(
        sources = (mapOf("crete-tv" to creteTv()) + extraIds.associateWith { FakeStationDataSource() }),
        names = mapOf("crete-tv" to "Crete TV", "radio-984" to "Radio 984"),
    )

    // ── stations ────────────────────────────────────────────────────────────

    @Test
    fun `stations lists only the caller's granted stations`() {
        val list = services(directory("radio-984")).stations(caller(grant("crete-tv")))
        assertEquals(1, list.size)
        assertEquals("crete-tv", list[0].id)
        assertEquals("Crete TV", list[0].name)
        assertEquals("NORMAL_USER", list[0].role)
        assertNull(list[0].clientCode)
    }

    @Test
    fun `stations surfaces the customer client code`() {
        val info = services(directory())
            .stations(caller(grant("crete-tv", UserRole.CUSTOMER_VIEWER, "30004521"))).single()
        assertEquals("CUSTOMER_VIEWER", info.role)
        assertEquals("30004521", info.clientCode)
    }

    // ── resolveStation ──────────────────────────────────────────────────────

    @Test
    fun `resolveStation rejects a missing station param`() {
        val e = assertFailsWith<McpToolException> {
            services(directory()).resolveStation(caller(grant("crete-tv")), null)
        }
        assertTrue(e.message!!.contains("required"))
    }

    @Test
    fun `resolveStation rejects a station the caller has no grant for`() {
        val e = assertFailsWith<McpToolException> {
            services(directory("radio-984")).resolveStation(caller(grant("crete-tv")), "radio-984")
        }
        assertTrue(e.message!!.contains("No access"))
    }

    @Test
    fun `resolveStation rejects an unhosted station even with a grant`() {
        val e = assertFailsWith<McpToolException> {
            services(directory()).resolveStation(caller(grant("ghost")), "ghost")
        }
        assertTrue(e.message!!.contains("Unknown"))
    }

    // ── role scoping ────────────────────────────────────────────────────────

    @Test
    fun `requireCode blocks a customer from querying another client code`() {
        val svc = services(directory())
        val customer = grant("crete-tv", UserRole.CUSTOMER_VIEWER, "AAA")
        assertFailsWith<McpToolException> { svc.requireCode(customer, "BBB") }
        svc.requireCode(customer, "AAA")                                   // own code passes
        svc.requireCode(grant("crete-tv", UserRole.NORMAL_USER), "BBB")    // staff passes
        svc.requireCode(grant("crete-tv", UserRole.REPORT_VIEWER), "BBB")
    }

    @Test
    fun `requireStaff blocks non-NORMAL_USER roles`() {
        val svc = services(directory())
        assertFailsWith<McpToolException> { svc.requireStaff(grant("crete-tv", UserRole.REPORT_VIEWER)) }
        assertFailsWith<McpToolException> { svc.requireStaff(grant("crete-tv", UserRole.CUSTOMER_VIEWER, "A")) }
        svc.requireStaff(grant("crete-tv", UserRole.NORMAL_USER))
    }

    // ── break resolution + the CUSTOMER_VIEWER row filter (a security rule) ──

    @Test
    fun `resolveBreak finds the slot by its HH mm label`() {
        val svc = services(directory())
        val access = svc.resolveStation(caller(grant("crete-tv")), "crete-tv")
        assertEquals(7L, svc.resolveBreak(access, "17:30").id)
    }

    @Test
    fun `resolveBreak rejects an unknown label`() {
        val svc = services(directory())
        val access = svc.resolveStation(caller(grant("crete-tv")), "crete-tv")
        val e = assertFailsWith<McpToolException> { svc.resolveBreak(access, "18:07") }
        assertTrue(e.message!!.contains("No break labelled"))
    }

    @Test
    fun `breakSpots returns every spot for a staff caller`() {
        val svc = services(directory())
        val access = svc.resolveStation(caller(grant("crete-tv")), "crete-tv")
        assertEquals(listOf("AAA", "BBB"), svc.breakSpots(access, date, "17:30").map { it.clientCode })
    }

    @Test
    fun `breakSpots hides other customers' spots from a CUSTOMER_VIEWER`() {
        val svc = services(directory())
        val access = svc.resolveStation(
            caller(grant("crete-tv", UserRole.CUSTOMER_VIEWER, "AAA")), "crete-tv",
        )
        val spots = svc.breakSpots(access, date, "17:30")
        assertEquals(1, spots.size)
        assertEquals("AAA", spots.single().clientCode)
    }

    @Test
    fun `breakSpots is empty for a date with no airings`() {
        val svc = services(directory())
        val access = svc.resolveStation(caller(grant("crete-tv")), "crete-tv")
        assertTrue(svc.breakSpots(access, LocalDate.of(2026, 7, 4), "17:30").isEmpty())
    }
}
