package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.server.scheduler.CommercialRow
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BreakReportAssemblerTest {

    private fun row(
        message: String,
        durationSeconds: Int,
        type: String = "Διαφημίσεις τηλεόρασης",
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
        type = type,
        contract = "C-1",
        excludeFromReports = excludeFromReports,
        flow = flow,
    )

    @Test
    fun `builds a ProgramFlow request with the template's parameter names`() {
        val request = BreakReportAssembler.buildBreakReport(
            date = LocalDate.of(2026, 7, 3),
            breakLabel = "17:30",
            commercials = listOf(row("SPOT A", 30), row("SPOT B", 20)),
        )
        assertEquals("ProgramFlowReport", request.reportId)
        assertEquals("ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ", request.parameters["REPORT_TITLE"]!!.jsonPrimitive.content)
        assertEquals("Κενός Χρόνος: 00:00", request.parameters["EMPTY_TIME"]!!.jsonPrimitive.content)
        // "<Greek day> - dd/MM/yyyy"
        val reportDate = request.parameters["REPORT_DATE"]!!.jsonPrimitive.content
        assertTrue(reportDate.endsWith(" - 03/07/2026"), "was: $reportDate")
        assertEquals("program-flow_2026-07-03_1730.pdf", request.fileName)
    }

    @Test
    fun `each row carries the break total and spot count, duration is MM colon SS`() {
        val request = BreakReportAssembler.buildBreakReport(
            date = LocalDate.of(2026, 7, 3),
            breakLabel = "17:30",
            commercials = listOf(row("SPOT A", 30), row("SPOT B", 20)),
        )
        assertEquals(2, request.rows.size)
        val first = request.rows.first()
        assertEquals("17:30", first["timeSlot"]!!.jsonPrimitive.content)
        assertEquals("00:30", first["duration"]!!.jsonPrimitive.content)
        assertEquals("00:50", first["groupTotalDuration"]!!.jsonPrimitive.content) // 30 + 20
        assertEquals(2, first["groupSpotCount"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `FLOW_ROH maps notes to the festive-programme label, other flows pass through`() {
        val request = BreakReportAssembler.buildBreakReport(
            date = LocalDate.of(2026, 7, 3),
            breakLabel = "17:30",
            commercials = listOf(
                row("FLOW SPOT", 30, flow = BreakReportAssembler.FLOW_ROH),
                row("PAID SPOT", 30, flow = "ΠΛΗΡΩΜΕΝΟ"),
            ),
        )
        assertEquals("ΕΟΡΤΑΣΤΙΚΟ ΠΡΟΓΡΑΜΜΑ", request.rows[0]["notes"]!!.jsonPrimitive.content)
        assertEquals("ΠΛΗΡΩΜΕΝΟ", request.rows[1]["notes"]!!.jsonPrimitive.content)
    }

    @Test
    fun `excludeFromReports rows are dropped and excluded from the totals`() {
        val request = BreakReportAssembler.buildBreakReport(
            date = LocalDate.of(2026, 7, 3),
            breakLabel = "17:30",
            commercials = listOf(
                row("VISIBLE", 30, id = 1),
                row("HIDDEN", 40, excludeFromReports = true, id = 2),
            ),
        )
        assertEquals(1, request.rows.size)
        assertEquals("VISIBLE", request.rows.single()["message"]!!.jsonPrimitive.content)
        // total reflects only the printable 30s, not the excluded 40s
        assertEquals("00:30", request.rows.single()["groupTotalDuration"]!!.jsonPrimitive.content)
    }
}
