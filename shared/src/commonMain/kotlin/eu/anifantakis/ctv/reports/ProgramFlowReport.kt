package eu.anifantakis.ctv.reports

import eu.anifantakis.ctv.reports.models.ProgramFlowReportData
import eu.anifantakis.ctv.reports.models.ReportConfig
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Payload builder for the Program Flow report (ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ).
 *
 * This file is the blueprint for adding a report: a JRXML template in
 * `reportcore/src/jvmMain/resources/reports/{id}.jrxml` plus one builder like
 * this that maps domain data to the parameter and field names the template
 * declares. Nothing else is per-report - the engine, server route, API
 * client, and ReportService are generic.
 */
object ProgramFlowReport {
    const val ID = "ProgramFlowReport"
}

fun ProgramFlowReportData.toReportPayload(config: ReportConfig): ReportPayload {
    val parameters = buildJsonObject {
        put("REPORT_TITLE", title)
        put("REPORT_DATE", formatGreekDate(date))
        put("EMPTY_TIME", emptyTimeFormatted)
        put("LOGO_PATH", config.logoPath)
    }

    // Flatten time-slot groups into detail rows (groupBy preserves encounter
    // order). Every row carries its group's totals; the template's group
    // footer prints them when the group ends.
    val rows = items.groupBy { it.timeSlot }.values.flatMap { group ->
        val last = group.last()
        group.map { item ->
            buildJsonObject {
                put("timeSlot", item.timeSlot)
                put("message", item.message)
                put("duration", item.duration)
                put("program", item.program)
                put("notes", item.notes)
                put("groupTotalDuration", last.groupTotalDuration)
                put("groupSpotCount", last.groupSpotCount)
            }
        }
    }

    return ReportPayload(ProgramFlowReport.ID, parameters, rows)
}

/**
 * Format a date the way the report shows it, e.g. "Παρασκευή - 03/07/2026".
 */
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
    // ISO form is zero-padded yyyy-MM-dd
    val (year, month, day) = date.toString().split("-")
    return "$greekDay - $day/$month/$year"
}
