package eu.anifantakis.commercials.mcp.tools.feature.generate_break_report

import eu.anifantakis.commercials.reports.dto.ReportRequest
import eu.anifantakis.commercials.reports.model.ProgramFlow
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * Builds a Program Flow (ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ) [ReportRequest] for a single break,
 * SERVER-SIDE from [StationDb][eu.anifantakis.commercials.server.scheduler.StationDb]
 * rows - so a report is produced headlessly, without the Compose app.
 *
 * The template contract (JRXML parameter/field names, MM:SS + Greek-date
 * formatting, the ΡΟΗ notes rule) lives in the shared `:reports-model`
 * [ProgramFlow] object, which the client's `ProgramFlowReport.toReportPayload`
 * also builds against - the two cannot drift apart. Only the input mapping
 * differs: the client starts from grid `CommercialItem`s, this from DB
 * [CommercialRow]s.
 *
 * Lives beside [GenerateBreakReportTool] because it is that tool's private
 * assembly step (its only caller).
 */
object BreakReportAssembler {

    fun buildBreakReport(
        date: LocalDate,
        breakLabel: String,
        commercials: List<CommercialRow>,
    ): ReportRequest {
        // calendar_excluded_docs contracts air but stay off printed reports.
        val printable = commercials.filter { !it.excludeFromReports }
        val totalSeconds = printable.sumOf { it.durationSeconds }

        val parameters: JsonObject = ProgramFlow.params(
            title = ProgramFlow.TITLE,
            reportDate = ProgramFlow.formatGreekDate(date.toKotlinLocalDate()),
            emptyTime = ProgramFlow.emptyTime(0),
            logoPath = null,
        )

        val rows: List<JsonObject> = printable.map { c ->
            ProgramFlow.row(
                timeSlot = breakLabel,
                message = c.message,
                duration = ProgramFlow.formatDuration(c.durationSeconds),
                program = c.type,
                notes = ProgramFlow.notes(c.flow),
                groupTotalDuration = ProgramFlow.formatDuration(totalSeconds),
                groupSpotCount = printable.size,
            )
        }

        val fileName = "program-flow_${date}_${breakLabel.replace(":", "")}.pdf"
        return ReportRequest(ProgramFlow.REPORT_ID, parameters, rows, fileName)
    }
}
