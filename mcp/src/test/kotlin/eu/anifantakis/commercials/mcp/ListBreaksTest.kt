package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.scheduler.BreakSlotRow
import eu.anifantakis.commercials.server.scheduler.BreakZone
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Break discovery: the grid, per-day occupancy, the "next break" filter, and
 * customer scoping — all against fakes, no MySQL.
 */
class ListBreaksTest {

    private val date = LocalDate.of(2026, 7, 3)

    private fun slot(id: Long, hour: Int, minute: Int, zone: BreakZone = BreakZone.DEFAULT) =
        BreakSlotRow(id = id, hour = hour, minute = minute, label = "%02d:%02d".format(hour, minute), zone = zone)

    // grid deliberately supplied OUT of order, to prove sorting
    private val grid = listOf(
        slot(3, 20, 0, BreakZone.PRIME),
        slot(1, 8, 0),
        slot(2, 14, 30, BreakZone.STANDARD),
    )

    /** 08:00 has one AAA + one BBB spot; 20:00 has one AAA spot; 14:30 is empty. */
    private fun station() = FakeStationDataSource(
        breaks = grid,
        byKey = mapOf(
            (1L to date) to listOf(commercial("AAA", "A1"), commercial("BBB", "B1", spotId = 2)),
            (3L to date) to listOf(commercial("AAA", "A2", spotId = 3)),
        ),
    )

    private fun svc() = services(FakeStationDirectory(sources = mapOf("crete-tv" to station())))
    private fun access(role: UserRole = UserRole.NORMAL_USER, code: String? = null) =
        svc().resolveStation(caller(grant("crete-tv", role, code)), "crete-tv")

    @Test
    fun `no date returns the whole grid, ascending by time, occupancy null`() {
        val s = svc()
        val breaks = s.listBreaks(access(), date = null, onlyWithSpots = false, after = null)
        assertEquals(listOf("08:00", "14:30", "20:00"), breaks.map { it.label })
        assertNull(breaks.first().spotCount)
        assertEquals("PRIME", breaks.last().zone)
    }

    @Test
    fun `with a date each break carries that day's occupancy`() {
        val s = svc()
        val byLabel = s.listBreaks(access(), date, onlyWithSpots = false, after = null).associateBy { it.label }
        assertEquals(2, byLabel["08:00"]!!.spotCount)
        assertEquals(60, byLabel["08:00"]!!.totalDurationSeconds)
        assertEquals(0, byLabel["14:30"]!!.spotCount)
        assertEquals(1, byLabel["20:00"]!!.spotCount)
    }

    @Test
    fun `onlyWithSpots drops the empty breaks`() {
        val s = svc()
        val labels = s.listBreaks(access(), date, onlyWithSpots = true, after = null).map { it.label }
        assertEquals(listOf("08:00", "20:00"), labels)
    }

    @Test
    fun `onlyWithSpots without a date is rejected`() {
        assertFailsWith<McpToolException> {
            svc().listBreaks(access(), date = null, onlyWithSpots = true, after = null)
        }
    }

    @Test
    fun `after finds the next break - first result is the next slot`() {
        val s = svc()
        val next = s.listBreaks(access(), date = null, onlyWithSpots = false, after = "09:15")
        assertEquals(listOf("14:30", "20:00"), next.map { it.label })
        assertEquals("14:30", next.first().label) // the "next break" after 09:15
    }

    @Test
    fun `after combines with onlyWithSpots - next OCCUPIED break`() {
        val s = svc()
        val next = s.listBreaks(access(), date, onlyWithSpots = true, after = "09:15")
        assertEquals(listOf("20:00"), next.map { it.label }) // 14:30 empty -> skipped
    }

    @Test
    fun `a malformed after is a clear error`() {
        assertFailsWith<McpToolException> { svc().listBreaks(access(), null, false, "25:00") }
        assertFailsWith<McpToolException> { svc().listBreaks(access(), null, false, "9pm") }
    }

    @Test
    fun `a CUSTOMER_VIEWER sees occupancy of only their own spots`() {
        val s = svc()
        val byLabel = s.listBreaks(
            access(UserRole.CUSTOMER_VIEWER, "AAA"), date, onlyWithSpots = false, after = null,
        ).associateBy { it.label }
        // 08:00 has AAA+BBB, but the AAA customer counts only their own
        assertEquals(1, byLabel["08:00"]!!.spotCount)
        assertEquals(1, byLabel["20:00"]!!.spotCount)
    }

    @Test
    fun `onlyWithSpots for a customer hides breaks where only OTHERS air`() {
        // give BBB a break of their own (id 2 / 14:30) so a customer AAA sees nothing there
        val ds = FakeStationDataSource(
            breaks = grid,
            byKey = mapOf(
                (1L to date) to listOf(commercial("AAA", "A1")),
                (2L to date) to listOf(commercial("BBB", "B1", spotId = 2)),
            ),
        )
        val s = services(FakeStationDirectory(sources = mapOf("crete-tv" to ds)))
        val access = s.resolveStation(caller(grant("crete-tv", UserRole.CUSTOMER_VIEWER, "AAA")), "crete-tv")
        val labels = s.listBreaks(access, date, onlyWithSpots = true, after = null).map { it.label }
        assertEquals(listOf("08:00"), labels) // 14:30 is BBB-only -> hidden from AAA
        assertTrue("14:30" !in labels)
    }
}
