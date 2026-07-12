package eu.anifantakis.commercials.reports.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Program Flow template contract. These run in commonTest, so every target
 * that renders (or feeds) the report — jvm, js, wasm, android, ios — proves the
 * SAME names, formatting and ΡΟΗ rule that the JRXML template declares.
 */
class ProgramFlowTest {

    @Test
    fun `report id and title match the jrxml template`() {
        assertEquals("ProgramFlowReport", ProgramFlow.REPORT_ID)
        assertEquals("ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ", ProgramFlow.TITLE)
        assertEquals("ΡΟΗ", ProgramFlow.FLOW_ROH)
    }

    @Test
    fun `formatDuration is zero-padded MM colon SS`() {
        assertEquals("00:00", ProgramFlow.formatDuration(0))
        assertEquals("00:30", ProgramFlow.formatDuration(30))
        assertEquals("01:30", ProgramFlow.formatDuration(90))
        assertEquals("60:00", ProgramFlow.formatDuration(3600))
    }

    @Test
    fun `formatGreekDate renders the Greek weekday then dd slash MM slash yyyy`() {
        assertEquals("Παρασκευή - 03/07/2026", ProgramFlow.formatGreekDate(LocalDate(2026, 7, 3)))
        assertEquals("Δευτέρα - 12/09/2005", ProgramFlow.formatGreekDate(LocalDate(2005, 9, 12)))
        assertEquals("Κυριακή - 01/02/2026", ProgramFlow.formatGreekDate(LocalDate(2026, 2, 1)))
    }

    @Test
    fun `emptyTime prefixes the formatted duration`() {
        assertEquals("Κενός Χρόνος: 00:00", ProgramFlow.emptyTime(0))
        assertEquals("Κενός Χρόνος: 01:23", ProgramFlow.emptyTime(83))
    }

    @Test
    fun `params carry the template's four parameter names`() {
        val p = ProgramFlow.params(
            title = ProgramFlow.TITLE,
            reportDate = "Παρασκευή - 03/07/2026",
            emptyTime = ProgramFlow.emptyTime(0),
            logoPath = "/tmp/logo.png",
        )
        assertEquals(setOf("REPORT_TITLE", "REPORT_DATE", "EMPTY_TIME", "LOGO_PATH"), p.keys)
        assertEquals("ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ", p["REPORT_TITLE"]!!.jsonPrimitive.content)
        assertEquals("/tmp/logo.png", p["LOGO_PATH"]!!.jsonPrimitive.content)
    }

    @Test
    fun `params emit JsonNull for an absent logo`() {
        val p = ProgramFlow.params(ProgramFlow.TITLE, "d", ProgramFlow.emptyTime(0), logoPath = null)
        assertEquals(JsonNull, p["LOGO_PATH"])
    }

    @Test
    fun `row carries the template's seven field names`() {
        val r = ProgramFlow.row(
            timeSlot = "17:30",
            message = "SPOT A",
            duration = "00:30",
            program = "Ειδήσεις",
            notes = "ΕΟΡΤΑΣΤΙΚΟ ΠΡΟΓΡΑΜΜΑ",
            groupTotalDuration = "00:50",
            groupSpotCount = 2,
        )
        assertEquals(
            setOf("timeSlot", "message", "duration", "program", "notes", "groupTotalDuration", "groupSpotCount"),
            r.keys,
        )
        assertEquals("17:30", r["timeSlot"]!!.jsonPrimitive.content)
        assertEquals(2, r["groupSpotCount"]!!.jsonPrimitive.int)
    }
}
