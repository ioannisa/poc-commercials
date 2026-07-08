package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.reports.dto.ReportRequest
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Builds a Program Flow ([ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ][PROGRAM_FLOW_REPORT_ID]) [ReportRequest]
 * for a single break, SERVER-SIDE from [StationDb][eu.anifantakis.commercials.server.scheduler.StationDb]
 * rows - mirroring the client's `ReportDataFactory.createBreakProgramFlowData` +
 * `ProgramFlowReport.toReportPayload`, and matching the parameter/field names the
 * `reports/ProgramFlowReport.jrxml` template declares.
 *
 * This deliberately re-implements the client's assembly rather than depending on
 * the client `reports-client` module (which would drag its Swing desktop service
 * onto the server), exactly as `EmailRoutes` assembles schedule-email data
 * server-side. TODO: if a third assembler ever appears, extract the pure-Kotlin
 * mapping (data model + toReportPayload) into a shared multiplatform module that
 * both `reports-client` and `:mcp` depend on.
 */
object BreakReportAssembler {

    /** Matches the classpath template `reports/ProgramFlowReport.jrxml` (client's `ProgramFlowReport.ID`). */
    const val PROGRAM_FLOW_REPORT_ID: String = "ProgramFlowReport"

    /**
     * The DB wire value marking a placement as part of the programme FLOW (ΡΟΗ),
     * i.e. `spots.flow == 'ΡΟΗ'`. This is the SAME data value as the client's
     * `grids.FLOW_ROH`; that constant lives in the (Compose) presentation cone,
     * so the backend keeps its own home for it here. Compared as DATA, never shown.
     */
    const val FLOW_ROH: String = "ΡΟΗ"

    fun buildBreakReport(
        date: LocalDate,
        breakLabel: String,
        commercials: List<CommercialRow>,
    ): ReportRequest {
        // calendar_excluded_docs contracts air but stay off printed reports.
        val printable = commercials.filter { !it.excludeFromReports }
        val totalSeconds = printable.sumOf { it.durationSeconds }

        val parameters: JsonObject = buildJsonObject {
            put("REPORT_TITLE", "ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ")
            put("REPORT_DATE", formatGreekDate(date))
            put("EMPTY_TIME", "Κενός Χρόνος: ${formatDuration(0)}")
            put("LOGO_PATH", null as String?)
        }

        val rows: List<JsonObject> = printable.map { c ->
            buildJsonObject {
                put("timeSlot", breakLabel)
                put("message", c.message)
                put("duration", formatDuration(c.durationSeconds))
                put("program", c.type)
                put("notes", if (c.flow == FLOW_ROH) "ΕΟΡΤΑΣΤΙΚΟ ΠΡΟΓΡΑΜΜΑ" else c.flow)
                put("groupTotalDuration", formatDuration(totalSeconds))
                put("groupSpotCount", printable.size)
            }
        }

        val fileName = "program-flow_${date}_${breakLabel.replace(":", "")}.pdf"
        return ReportRequest(PROGRAM_FLOW_REPORT_ID, parameters, rows, fileName)
    }

    /** MM:SS, matching the client's `ReportDataFactory.formatDuration`. */
    private fun formatDuration(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }

    /** e.g. "Παρασκευή - 03/07/2026", matching the client's Greek date format. */
    private fun formatGreekDate(date: LocalDate): String {
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
        val d = date.dayOfMonth.toString().padStart(2, '0')
        val m = date.monthValue.toString().padStart(2, '0')
        return "$greekDay - $d/$m/${date.year}"
    }
}
