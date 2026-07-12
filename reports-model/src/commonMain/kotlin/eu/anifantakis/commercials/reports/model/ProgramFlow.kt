package eu.anifantakis.commercials.reports.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Program Flow (ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ) report's shared contract: the JRXML
 * parameter/field NAMES the `reports/ProgramFlowReport.jrxml` template declares,
 * the formatters, the ΡΟΗ notes rule, and the JSON param/row builders.
 *
 * The producer of report DATA differs per side (the client from grid
 * `CommercialItem`s, the backend from station-DB `CommercialRow`s), but BOTH
 * must emit exactly these names/formatting - so both build their params/rows
 * through here and wrap the result (client -> ReportPayload, backend ->
 * reportcore.ReportRequest).
 */
object ProgramFlow {

    /** Resolves to the classpath template `reports/ProgramFlowReport.jrxml`. */
    const val REPORT_ID: String = "ProgramFlowReport"

    /** The report's fixed Greek title. */
    const val TITLE: String = "ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ"

    /**
     * The DB/grid wire value marking a placement as part of the programme FLOW
     * (ΡΟΗ) rather than a paid break. Compared as DATA (never shown); see [notes].
     * Same value as the grid UI's `grids.FLOW_ROH`, which stays the home of the
     * UI-side comparison.
     */
    const val FLOW_ROH: String = "ΡΟΗ"

    /** MM:SS. */
    fun formatDuration(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }

    /** e.g. "Παρασκευή - 03/07/2026". */
    fun formatGreekDate(date: LocalDate): String {
        val greekDay = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "Δευτέρα"
            DayOfWeek.TUESDAY -> "Τρίτη"
            DayOfWeek.WEDNESDAY -> "Τετάρτη"
            DayOfWeek.THURSDAY -> "Πέμπτη"
            DayOfWeek.FRIDAY -> "Παρασκευή"
            DayOfWeek.SATURDAY -> "Σάββατο"
            DayOfWeek.SUNDAY -> "Κυριακή"
            else -> ""
        }
        val d = date.day.toString().padStart(2, '0')
        val m = date.month.number.toString().padStart(2, '0')
        return "$greekDay - $d/$m/${date.year}"
    }

    /** The report's "empty time" line, e.g. "Κενός Χρόνος: 00:00". */
    fun emptyTime(seconds: Int): String = "Κενός Χρόνος: ${formatDuration(seconds)}"

    /** A placement's notes cell: the festive-programme label for ΡΟΗ, else the raw flow. */
    fun notes(flow: String): String =
        if (flow == FLOW_ROH) "ΕΟΡΤΑΣΤΙΚΟ ΠΡΟΓΡΑΜΜΑ" else flow

    /** The template's `<parameter>` values. */
    fun params(title: String, reportDate: String, emptyTime: String, logoPath: String?): JsonObject =
        buildJsonObject {
            put("REPORT_TITLE", title)
            put("REPORT_DATE", reportDate)
            put("EMPTY_TIME", emptyTime)
            put("LOGO_PATH", logoPath)
        }

    /** One detail row, keyed by the template's `<field>` names. */
    fun row(
        timeSlot: String,
        message: String,
        duration: String,
        program: String,
        notes: String,
        groupTotalDuration: String,
        groupSpotCount: Int,
    ): JsonObject = buildJsonObject {
        put("timeSlot", timeSlot)
        put("message", message)
        put("duration", duration)
        put("program", program)
        put("notes", notes)
        put("groupTotalDuration", groupTotalDuration)
        put("groupSpotCount", groupSpotCount)
    }
}
