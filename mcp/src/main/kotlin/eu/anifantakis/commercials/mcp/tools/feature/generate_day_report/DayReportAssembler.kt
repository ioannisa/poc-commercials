package eu.anifantakis.commercials.mcp.tools.feature.generate_day_report

import eu.anifantakis.commercials.reports.dto.ReportRequest
import eu.anifantakis.commercials.reports.model.ProgramFlow
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * Builds a whole-day Program Flow (ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ) [ReportRequest] SERVER-SIDE:
 * ONE report whose detail rows span EVERY occupied break of the day, in air
 * order, each row tagged with its break's `timeSlot` and that break's group
 * totals - the JRXML groups on `timeSlot`, so this renders as a per-break
 * section, exactly like the client's `ReportDataFactory.createProgramFlowData`.
 *
 * The single-break [BreakReportAssembler][eu.anifantakis.commercials.mcp.tools.feature.generate_break_report.BreakReportAssembler]
 * is the one-group special case of this. Both go through the shared
 * `:reports-model` [ProgramFlow] contract so parameter/field names and MM:SS /
 * Greek-date formatting cannot drift. Lives beside [GenerateDayReportTool] (its
 * only caller).
 */
object DayReportAssembler {

    /**
     * @param breaks the day's occupied breaks IN AIR ORDER, each as
     *   (`HH:mm` label, its already customer-scoped commercials). Rows flagged
     *   `excludeFromReports` are dropped here and a break left empty is skipped,
     *   so group totals count only what prints.
     */
    fun buildDayReport(
        date: LocalDate,
        breaks: List<Pair<String, List<CommercialRow>>>,
        /** This station's logo (server.yaml); null prints the placeholder. */
        logoPath: String? = null,
    ): ReportRequest {
        val parameters: JsonObject = ProgramFlow.params(
            title = ProgramFlow.TITLE,
            reportDate = ProgramFlow.formatGreekDate(date.toKotlinLocalDate()),
            emptyTime = ProgramFlow.emptyTime(0),
            logoPath = logoPath,
        )

        val rows: List<JsonObject> = buildList {
            breaks.forEach { (label, commercials) ->
                // calendar_excluded_docs contracts air but stay off printed reports.
                val printable = commercials.filter { !it.excludeFromReports }
                if (printable.isEmpty()) return@forEach
                val totalSeconds = printable.sumOf { it.durationSeconds }
                // ONE break airs inside ONE programme (the slot's, not each spot's).
                // Take it from the first placement that carries it - the same rule
                // the client's SchedulerCellData.programName follows.
                val programme = printable.firstNotNullOfOrNull { it.programName }.orEmpty()
                printable.forEach { c ->
                    add(
                        ProgramFlow.row(
                            timeSlot = label,
                            message = c.message,
                            duration = ProgramFlow.formatDuration(c.durationSeconds),
                            program = programme,
                            // Blank on purpose: the operator writes notes by hand.
                            notes = "",
                            groupTotalDuration = ProgramFlow.formatDuration(totalSeconds),
                            groupSpotCount = printable.size,
                        )
                    )
                }
            }
        }

        val fileName = "program-flow_${date}_day.pdf"
        return ReportRequest(ProgramFlow.REPORT_ID, parameters, rows, fileName)
    }
}
