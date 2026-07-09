package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.reports.model.ProgramFlow
import eu.anifantakis.commercials.reports.models.ProgramFlowReportData
import eu.anifantakis.commercials.reports.models.ReportConfig

/**
 * Payload builder for the Program Flow report (ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ).
 *
 * This file is the blueprint for adding a report: a JRXML template in
 * `reportcore/src/jvmMain/resources/reports/{id}.jrxml` plus one builder like
 * this that maps domain data to the parameter and field names the template
 * declares. Nothing else is per-report - the engine, server route, API
 * client, and ReportService are generic.
 *
 * The template's names, formatters and the ΡΟΗ notes rule live in the shared
 * `:reports-model` [ProgramFlow] contract, so this client builder and the
 * backend's MCP assembler cannot drift apart.
 */
object ProgramFlowReport {
    const val ID = ProgramFlow.REPORT_ID
}

fun ProgramFlowReportData.toReportPayload(config: ReportConfig): ReportPayload {
    val parameters = ProgramFlow.params(
        title = title,
        reportDate = ProgramFlow.formatGreekDate(date),
        emptyTime = emptyTimeFormatted,
        logoPath = config.logoPath,
    )

    // Flatten time-slot groups into detail rows (groupBy preserves encounter
    // order). Every row carries its group's totals; the template's group
    // footer prints them when the group ends.
    val rows = items.groupBy { it.timeSlot }.values.flatMap { group ->
        val last = group.last()
        group.map { item ->
            ProgramFlow.row(
                timeSlot = item.timeSlot,
                message = item.message,
                duration = item.duration,
                program = item.program,
                notes = item.notes,
                groupTotalDuration = last.groupTotalDuration,
                groupSpotCount = last.groupSpotCount,
            )
        }
    }

    return ReportPayload(ProgramFlowReport.ID, parameters, rows)
}
