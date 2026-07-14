package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mcp.FakeStationDataSource
import eu.anifantakis.commercials.mcp.FakeStationDirectory
import eu.anifantakis.commercials.mcp.McpToolException
import eu.anifantakis.commercials.mcp.McpToolServices
import eu.anifantakis.commercials.mcp.StationAccess
import eu.anifantakis.commercials.mcp.caller
import eu.anifantakis.commercials.mcp.commercial
import eu.anifantakis.commercials.mcp.grant
import eu.anifantakis.commercials.mcp.services
import eu.anifantakis.commercials.server.auth.UserRole
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Break discovery: the day's breaks, per-day occupancy, the "next break" filter,
 * and customer scoping — all against fakes, no MySQL. Drives the tool's own pure
 * [listBreaks] logic (with the real [McpToolServices.isCustomerScoped] predicate).
 *
 * The breaks now come from the month cells alone — a break IS the time an airing
 * landed on — so there is no catalog to hand the fake, and a break with nothing
 * in it cannot exist for staff (only for a customer who sees none of its spots).
 */
class ListBreaksTest {

    private val date = LocalDate.of(2026, 7, 3)

    /** Exercise the tool's logic the way its handler does: real scoping + pure break math. */
    private fun McpToolServices.breaks(
        access: StationAccess,
        date: LocalDate,
        onlyWithSpots: Boolean,
        after: String?,
    ) = listBreaks(access, isCustomerScoped(access.grant), date, onlyWithSpots, after)

    private fun at(hour: Int, minute: Int) = LocalTime.of(hour, minute)

    /**
     * 08:00 has one AAA + one BBB spot; 14:30 is BBB-only; 20:00 has one AAA spot.
     * Deliberately supplied OUT of time order, to prove the sorting.
     */
    private fun station() = FakeStationDataSource(
        byKey = mapOf(
            (at(20, 0) to date) to listOf(commercial("AAA", "A2", spotId = 3)),
            (at(8, 0) to date) to listOf(commercial("AAA", "A1"), commercial("BBB", "B1", spotId = 2)),
            (at(14, 30) to date) to listOf(commercial("BBB", "B2", spotId = 4)),
        ),
    )

    private fun svc() = services(FakeStationDirectory(sources = mapOf("crete-tv" to station())))
    private fun access(role: UserRole = UserRole.NORMAL_USER, code: String? = null) =
        svc().resolveStation(caller(grant("crete-tv", role, code)), "crete-tv")

    @Test
    fun `the day's breaks are its distinct airing times, ascending, with their zone`() {
        val s = svc()
        val breaks = s.breaks(access(), date, onlyWithSpots = false, after = null)
        assertEquals(listOf("08:00", "14:30", "20:00"), breaks.map { it.label })
        assertEquals("PRIME", breaks.last().zone)
    }

    @Test
    fun `each break carries that day's occupancy`() {
        val s = svc()
        val byLabel = s.breaks(access(), date, onlyWithSpots = false, after = null).associateBy { it.label }
        assertEquals(2, byLabel["08:00"]!!.spotCount)
        assertEquals(60, byLabel["08:00"]!!.totalDurationSeconds)
        assertEquals(1, byLabel["14:30"]!!.spotCount)
        assertEquals(1, byLabel["20:00"]!!.spotCount)
    }

    /**
     * Was "onlyWithSpots drops the empty breaks". For staff it can no longer drop
     * anything: a break exists BECAUSE something aired in it. The filter earns its
     * keep only under customer scoping (the two tests at the bottom).
     */
    @Test
    fun `onlyWithSpots is a no-op for staff - every break of the day has spots`() {
        val s = svc()
        val all = s.breaks(access(), date, onlyWithSpots = false, after = null).map { it.label }
        val filtered = s.breaks(access(), date, onlyWithSpots = true, after = null).map { it.label }
        assertEquals(all, filtered)
        assertEquals(listOf("08:00", "14:30", "20:00"), filtered)
    }

    /**
     * Was "onlyWithSpots without a date is rejected" — a runtime guard, because
     * `date` used to be optional (the breaks came from a station-wide catalog).
     * A break exists per DAY now, so the requirement moved into the schema, where
     * the MCP client enforces it before the tool ever runs.
     */
    @Test
    fun `date is a REQUIRED argument - a break exists per day, not per station`() {
        val required = assertNotNull(ListBreaksTool.inputSchema.required)
        assertTrue("date" in required, "was: $required")
    }

    @Test
    fun `after finds the next break - first result is the next slot`() {
        val s = svc()
        val next = s.breaks(access(), date, onlyWithSpots = false, after = "09:15")
        assertEquals(listOf("14:30", "20:00"), next.map { it.label })
        assertEquals("14:30", next.first().label) // the "next break" after 09:15
    }

    @Test
    fun `after combines with onlyWithSpots - next OCCUPIED break`() {
        val s = svc()
        // for the AAA customer 14:30 is empty (it is BBB-only) -> skipped
        val next = s.breaks(
            access(UserRole.CUSTOMER_VIEWER, "AAA"), date, onlyWithSpots = true, after = "09:15",
        )
        assertEquals(listOf("20:00"), next.map { it.label })
    }

    @Test
    fun `a malformed after is a clear error`() {
        assertFailsWith<McpToolException> { svc().breaks(access(), date, false, "25:00") }
        assertFailsWith<McpToolException> { svc().breaks(access(), date, false, "9pm") }
    }

    @Test
    fun `a CUSTOMER_VIEWER sees occupancy of only their own spots`() {
        val s = svc()
        val byLabel = s.breaks(
            access(UserRole.CUSTOMER_VIEWER, "AAA"), date, onlyWithSpots = false, after = null,
        ).associateBy { it.label }
        // 08:00 has AAA+BBB, but the AAA customer counts only their own
        assertEquals(1, byLabel["08:00"]!!.spotCount)
        assertEquals(0, byLabel["14:30"]!!.spotCount) // BBB-only -> visible but empty
        assertEquals(1, byLabel["20:00"]!!.spotCount)
    }

    @Test
    fun `onlyWithSpots for a customer hides breaks where only OTHERS air`() {
        val s = svc()
        val access = s.resolveStation(caller(grant("crete-tv", UserRole.CUSTOMER_VIEWER, "AAA")), "crete-tv")
        val labels = s.breaks(access, date, onlyWithSpots = true, after = null).map { it.label }
        assertEquals(listOf("08:00", "20:00"), labels) // 14:30 is BBB-only -> hidden from AAA
        assertTrue("14:30" !in labels)
    }
}
