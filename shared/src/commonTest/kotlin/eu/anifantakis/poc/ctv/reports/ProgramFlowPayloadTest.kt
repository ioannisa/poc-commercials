package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ProgramFlowItem
import eu.anifantakis.poc.ctv.reports.models.ProgramFlowReportData
import eu.anifantakis.poc.ctv.reports.models.ReportConfig
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgramFlowPayloadTest {

    private fun item(
        timeSlot: String,
        message: String,
        groupTotalDuration: String = "",
        groupSpotCount: Int = 0,
    ) = ProgramFlowItem(
        timeSlot = timeSlot,
        timeSlotTime = LocalTime(8, 0),
        message = message,
        duration = "00:30",
        durationSeconds = 30,
        program = "Ειδήσεις",
        notes = "",
        groupTotalDuration = groupTotalDuration,
        groupSpotCount = groupSpotCount,
    )

    private fun data(items: List<ProgramFlowItem>) = ProgramFlowReportData(
        generatedAt = 0L,
        date = LocalDate(2026, 7, 3), // a Friday
        dateFormatted = "unused",
        emptyTimeFormatted = "Κενός Χρόνος: 01:23",
        items = items,
    )

    @Test
    fun buildsParametersIncludingGreekFormattedDate() {
        val payload = data(listOf(item("08:00", "A")))
            .toReportPayload(ReportConfig(logoPath = "/tmp/logo.png"))

        assertEquals(ProgramFlowReport.ID, payload.reportId)
        assertEquals("ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ", payload.parameters["REPORT_TITLE"]?.jsonPrimitive?.content)
        assertEquals("Παρασκευή - 03/07/2026", payload.parameters["REPORT_DATE"]?.jsonPrimitive?.content)
        assertEquals("Κενός Χρόνος: 01:23", payload.parameters["EMPTY_TIME"]?.jsonPrimitive?.content)
        assertEquals("/tmp/logo.png", payload.parameters["LOGO_PATH"]?.jsonPrimitive?.content)
    }

    @Test
    fun logoPathIsNullWhenNotConfigured() {
        val payload = data(listOf(item("08:00", "A"))).toReportPayload(ReportConfig())

        assertEquals(JsonNull, payload.parameters["LOGO_PATH"])
    }

    @Test
    fun flattensGroupsAndStampsEveryRowWithItsGroupTotalsFromTheLastItem() {
        val payload = data(
            listOf(
                item("08:00", "A"),
                item("08:00", "B", groupTotalDuration = "01:30", groupSpotCount = 2),
                item("09:00", "C", groupTotalDuration = "00:30", groupSpotCount = 1),
            )
        ).toReportPayload(ReportConfig())

        assertEquals(3, payload.rows.size)
        assertEquals(listOf("A", "B", "C"), payload.rows.map { it["message"]?.jsonPrimitive?.content })
        // both 08:00 rows carry the totals held by the group's last item
        assertEquals("01:30", payload.rows[0]["groupTotalDuration"]?.jsonPrimitive?.content)
        assertEquals(2, payload.rows[0]["groupSpotCount"]?.jsonPrimitive?.int)
        assertEquals("01:30", payload.rows[1]["groupTotalDuration"]?.jsonPrimitive?.content)
        assertEquals("00:30", payload.rows[2]["groupTotalDuration"]?.jsonPrimitive?.content)
        assertEquals(1, payload.rows[2]["groupSpotCount"]?.jsonPrimitive?.int)
    }
}
