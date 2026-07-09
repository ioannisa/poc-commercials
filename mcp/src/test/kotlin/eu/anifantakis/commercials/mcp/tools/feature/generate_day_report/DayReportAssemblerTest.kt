package eu.anifantakis.commercials.mcp.tools.feature.generate_day_report

import eu.anifantakis.commercials.server.scheduler.CommercialRow
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayReportAssemblerTest {

    private val date = LocalDate.of(2005, 9, 12)

    private fun row(
        message: String,
        durationSeconds: Int,
        flow: String = "",
        excludeFromReports: Boolean = false,
        id: Long = 1,
    ) = CommercialRow(
        id = id,
        spotId = id,
        position = 0,
        clientCode = "30004521",
        clientName = "ΠΕΛΑΤΗΣ",
        message = message,
        durationSeconds = durationSeconds,
        type = "Διαφημίσεις τηλεόρασης",
        contract = "C-1",
        excludeFromReports = excludeFromReports,
        flow = flow,
    )

    @Test
    fun `spans every occupied break in the given order, each row tagged with its break`() {
        val request = DayReportAssembler.buildDayReport(
            date,
            breaks = listOf(
                "12:30" to listOf(row("A", 30), row("B", 24, id = 2)),
                "21:00" to listOf(row("C", 20, id = 3)),
            ),
        )
        assertEquals("ProgramFlowReport", request.reportId)
        assertEquals("program-flow_2005-09-12_day.pdf", request.fileName)
        val slots = request.rows.map { it["timeSlot"]!!.jsonPrimitive.content }
        assertEquals(listOf("12:30", "12:30", "21:00"), slots) // order preserved, one row per spot
        val reportDate = request.parameters["REPORT_DATE"]!!.jsonPrimitive.content
        assertTrue(reportDate.endsWith(" - 12/09/2005"), "was: $reportDate")
    }

    @Test
    fun `group totals are per-break, not day-wide`() {
        val request = DayReportAssembler.buildDayReport(
            date,
            breaks = listOf(
                "12:30" to listOf(row("A", 30), row("B", 24, id = 2)), // 2 spots / 00:54
                "21:00" to listOf(row("C", 20, id = 3)),               // 1 spot / 00:20
            ),
        )
        val first = request.rows.first()
        assertEquals("00:54", first["groupTotalDuration"]!!.jsonPrimitive.content)
        assertEquals(2, first["groupSpotCount"]!!.jsonPrimitive.content.toInt())
        val last = request.rows.last()
        assertEquals("00:20", last["groupTotalDuration"]!!.jsonPrimitive.content)
        assertEquals(1, last["groupSpotCount"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `excludeFromReports rows are dropped and a break left empty is skipped`() {
        val request = DayReportAssembler.buildDayReport(
            date,
            breaks = listOf(
                "12:30" to listOf(row("VISIBLE", 30), row("HIDDEN", 40, excludeFromReports = true, id = 2)),
                "21:00" to listOf(row("ALL-HIDDEN", 15, excludeFromReports = true, id = 3)), // whole break drops
            ),
        )
        val slots = request.rows.map { it["timeSlot"]!!.jsonPrimitive.content }
        assertEquals(listOf("12:30"), slots) // 21:00 skipped entirely
        assertEquals("VISIBLE", request.rows.single()["message"]!!.jsonPrimitive.content)
        // group total reflects only the printable 30s, not the excluded 40s
        assertEquals("00:30", request.rows.single()["groupTotalDuration"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no occupied breaks yields an empty request (the tool turns this into a clear error)`() {
        val request = DayReportAssembler.buildDayReport(date, breaks = emptyList())
        assertTrue(request.rows.isEmpty())
    }
}
