package eu.anifantakis.commercials.scheduleemail

import eu.anifantakis.commercials.server.scheduler.BreakSlotRow
import eu.anifantakis.commercials.server.scheduler.BreakZone
import eu.anifantakis.commercials.server.scheduler.CellRow
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.scheduler.StationDb
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The triangular CUS/TRA assembly — the nastiest logic in the codebase, now
 * reachable because the assembler organizes reads through [ScheduleEmailSource]
 * instead of importing the concrete JDBC StationDb.
 *
 * Fixture: July 2026, one break at 20:00. Spot 10 airs on the 1st and the 2nd,
 * spot 11 only on the 2nd. Both spots BELONG to customer AAA, but their contract
 * is PAID BY trader BBB (the triangular case).
 */
class ScheduleEmailAssembleTest {

    private val break2000 = BreakSlotRow(id = 1, hour = 20, minute = 0, label = "20:00", zone = BreakZone.PRIME)
    private val day1 = LocalDate.of(2026, 7, 1)
    private val day2 = LocalDate.of(2026, 7, 2)

    private fun spot(spotId: Long, message: String) = CommercialRow(
        id = spotId * 100,
        spotId = spotId,
        position = 0,
        clientCode = "AAA",
        clientName = "ΠΕΛΑΤΗΣ Α",
        message = message,
        durationSeconds = 30,
        type = "Διαφημίσεις τηλεόρασης",
        contract = "C-1",
        flow = "ΡΟΗ",
        programName = "Ειδήσεις",
        programColorArgb = 0x00FF00,
        payerCode = "BBB",
        payerName = "ΠΡΑΚΤΟΡΕΙΟ Β",
    )

    private fun source() = FakeScheduleEmailSource(
        customers = mapOf(
            "AAA" to StationDb.CustomerContact("ΠΕΛΑΤΗΣ Α", "a@example.gr"),
            "BBB" to StationDb.CustomerContact("ΠΡΑΚΤΟΡΕΙΟ Β", "b@example.gr"),
        ),
        breaks = listOf(break2000),
        cells = listOf(
            CellRow(breakId = 1, date = day1, spotCount = 1, totalDurationSeconds = 30, zoneColorArgb = 0xFF00FF, commercials = emptyList()),
            CellRow(breakId = 1, date = day2, spotCount = 2, totalDurationSeconds = 60, zoneColorArgb = 0xFF00FF, commercials = emptyList()),
        ),
        commercialsByKey = mapOf(
            (1L to day1) to listOf(spot(10, "SPOT A")),
            (1L to day2) to listOf(spot(10, "SPOT A"), spot(11, "SPOT B")),
        ),
    )

    private fun assemble(clientCode: String, byTrader: Boolean, spotIds: Set<Long> = emptySet()) =
        ScheduleEmailAssembler.assemble(
            source = source(),
            stationName = "Crete TV",
            year = 2026, month = 7,
            clientCode = clientCode, byTrader = byTrader,
            spotIds = spotIds, personalMessage = null,
        )

    @Test
    fun `unknown customer yields no email`() {
        assertNull(assemble(clientCode = "ZZZ", byTrader = false))
    }

    @Test
    fun `a party with no airings that month yields no email`() {
        val empty = FakeScheduleEmailSource(
            customers = mapOf("AAA" to StationDb.CustomerContact("ΠΕΛΑΤΗΣ Α", null)),
            breaks = listOf(break2000),
        )
        assertNull(
            ScheduleEmailAssembler.assemble(empty, "Crete TV", 2026, 7, "AAA", false, emptySet(), null)
        )
    }

    @Test
    fun `customer mode builds one section per spot, busiest first`() {
        val data = assemble(clientCode = "AAA", byTrader = false)!!

        assertEquals("Crete TV", data.stationName)
        assertEquals("ΠΕΛΑΤΗΣ Α", data.customerName)
        assertEquals(2, data.spots.size)
        // spot 10 aired twice, spot 11 once -> busiest first
        assertEquals("SPOT A", data.spots[0].description)
        assertEquals("SPOT B", data.spots[1].description)
    }

    @Test
    fun `the grid is day-indexed with one row per used break`() {
        val data = assemble(clientCode = "AAA", byTrader = false)!!
        val sectionA = data.spots.first { it.description == "SPOT A" }

        assertEquals(1, sectionA.rows.size)
        val row = sectionA.rows.single()
        assertEquals("20:00", row.label)
        assertEquals(31, row.cells.size)              // July has 31 days
        assertEquals(1, row.cells[0]?.count)          // day 1
        assertEquals(1, row.cells[1]?.count)          // day 2
        assertNull(row.cells[2])                      // day 3: no airing
        assertEquals(0xFF00FF, row.cells[0]?.colorArgb)
    }

    @Test
    fun `per-programme totals count the party's placements`() {
        val data = assemble(clientCode = "AAA", byTrader = false)!!
        val totals = data.spots.first { it.description == "SPOT A" }.programTotals

        assertEquals(1, totals.size)
        assertEquals("Ειδήσεις", totals.single().name)
        assertEquals(2, totals.single().spots)         // aired on day 1 and day 2
    }

    @Test
    fun `trader mode selects by PAYER and labels the spots with their END CLIENT`() {
        val data = assemble(clientCode = "BBB", byTrader = true)!!

        assertEquals("ΠΡΑΚΤΟΡΕΙΟ Β", data.customerName)
        assertEquals(2, data.spots.size)
        // the agency pays, but the spots are the end client's -> label them
        assertTrue(data.spots.all { it.description.endsWith(" — ΠΕΛΑΤΗΣ Α") }, "was: ${data.spots.map { it.description }}")
    }

    @Test
    fun `the payer sees nothing in customer mode, and the end client nothing in trader mode`() {
        assertNull(assemble(clientCode = "BBB", byTrader = false)) // BBB owns no spots
        assertNull(assemble(clientCode = "AAA", byTrader = true))  // AAA pays no contract
    }

    @Test
    fun `spotIds restricts the email to the chosen creatives`() {
        val data = assemble(clientCode = "AAA", byTrader = false, spotIds = setOf(11L))!!
        assertEquals(1, data.spots.size)
        assertEquals("SPOT B", data.spots.single().description)
    }
}
