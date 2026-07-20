package eu.anifantakis.commercials.feature.timetable.presentation.reports

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.toUi
import eu.anifantakis.commercials.feature.timetable.presentation.screens.reportConfig
import eu.anifantakis.commercials.grids.BreakSlot
import eu.anifantakis.commercials.grids.SchedulerCellData
import eu.anifantakis.commercials.grids.SchedulerKey
import eu.anifantakis.commercials.grids.formatTime
import eu.anifantakis.commercials.reports.ReportDataFactory
import eu.anifantakis.commercials.reports.ReportPayload
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.StationLogoCache
import eu.anifantakis.commercials.reports.models.ReportResult
import eu.anifantakis.commercials.reports.print
import eu.anifantakis.commercials.reports.toReportPayload
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * What every report reads off the grid: the month on screen, its rows and
 * its cells. Passed in rather than held, because the grid's state belongs to
 * the ViewModel - the controller is stateless and one report long.
 */
data class ReportContext(
    val year: Int,
    val month: Int,
    val breaks: ImmutableList<BreakSlot>,
    val cells: ImmutableMap<SchedulerKey, SchedulerCellData>,
)

/**
 * What a report run has to say. The controller NEVER shows a snackbar: it
 * returns what to say, and the ViewModel says it - so the app keeps ONE
 * error policy and this class stays testable without a global container.
 */
sealed interface ReportOutcome {
    /** It ran and there is nothing to tell the user (a print job went out). */
    data object Silent : ReportOutcome

    /** Surface this through the app's one snackbar. */
    data class Notify(val message: UiText) : ReportOutcome
}

/** The month toolbar's three actions - same payloads, different destination. */
enum class MonthReportMode { PREVIEW, PRINT, EXPORT_PDF }

/**
 * Assembles and runs the timetable's reports (kmp-developer domain
 * CONTROLLER: several steps that belong together, one entry point per
 * report, so the caller gets a result instead of a procedure).
 *
 * It lives in PRESENTATION, not `domain/usecase` where the skill puts
 * controllers, and that is deliberate: [ReportService] and
 * [StationLogoCache] are in :reports-client, which only the presentation
 * module depends on. Printing is a platform capability, not a domain rule -
 * pulling :reports-client down into :domain to satisfy a folder convention
 * would invert the dependency for no gain.
 *
 * Stateless: the `reportBusy` guard stays with the ViewModel, because it is
 * SCREEN state (it disables the toolbar) and its try/finally must survive a
 * cancelled save dialog.
 */
class ScheduleReportsController(
    private val scheduleRepository: ScheduleRepository,
    private val reportService: ReportService,
    private val logoCache: StationLogoCache,
) {

    /** Desktop/browser can generate reports; mobile cannot. */
    fun isAvailable(): Boolean = reportService.isReportGenerationAvailable()

    /** ONE day, printed. */
    suspend fun printDay(ctx: ReportContext, date: LocalDate): ReportOutcome {
        val cells = when (val slice = cellsWithCommercials(ctx, date = date)) {
            is Slice.Failed -> return ReportOutcome.Notify(slice.error)
            is Slice.Ready -> slice.cells
        }
        val data = ReportDataFactory.createProgramFlowData(date, ctx.breaks, cells)
        if (data.items.isEmpty()) return ReportOutcome.Silent
        reportService.print(data.toReportPayload(logoCache.reportConfig()))
        return ReportOutcome.Silent
    }

    /** ONE break on ONE day, printed. */
    suspend fun printBreak(ctx: ReportContext, time: LocalTime, date: LocalDate): ReportOutcome {
        val slot = ctx.breaks.firstOrNull { it.time == time } ?: return ReportOutcome.Silent
        val cells = when (val slice = cellsWithCommercials(ctx, date = date, time = time)) {
            is Slice.Failed -> return ReportOutcome.Notify(slice.error)
            is Slice.Ready -> slice.cells
        }
        val cell = cells[SchedulerKey(time, date)] ?: return ReportOutcome.Silent
        val data = ReportDataFactory.createBreakProgramFlowData(
            date = date,
            breakTimeLabel = formatTime(slot.time.hour, slot.time.minute),
            commercials = cell.commercials,
            programName = cell.programName,
        )
        if (data.items.isEmpty()) return ReportOutcome.Silent
        reportService.print(data.toReportPayload(logoCache.reportConfig()))
        return ReportOutcome.Silent
    }

    /** ONE break across the whole month - not the month's every airing. */
    suspend fun printBreakAcrossMonth(ctx: ReportContext, time: LocalTime): ReportOutcome {
        val slot = ctx.breaks.firstOrNull { it.time == time } ?: return ReportOutcome.Silent
        val cells = when (val slice = cellsWithCommercials(ctx, time = time)) {
            is Slice.Failed -> return ReportOutcome.Notify(slice.error)
            is Slice.Ready -> slice.cells
        }
        val config = logoCache.reportConfig()
        val payloads = ReportDataFactory
            .createMonthProgramFlowData(ctx.year, ctx.month, listOf(slot), cells)
            .map { it.toReportPayload(config) }
        if (payloads.isEmpty()) return ReportOutcome.Silent
        reportService.print(payloads)
        return ReportOutcome.Silent
    }

    /**
     * The WHOLE visible month, three ways. The only report that genuinely
     * wants every airing - and it is an explicit user action, not a screen
     * load. An empty month never reaches the service.
     */
    suspend fun runMonth(ctx: ReportContext, mode: MonthReportMode): ReportOutcome {
        val cells = when (val slice = cellsWithCommercials(ctx)) {
            is Slice.Failed -> return ReportOutcome.Notify(slice.error)
            is Slice.Ready -> slice.cells
        }
        val data = ReportDataFactory.createMonthProgramFlowData(ctx.year, ctx.month, ctx.breaks, cells)
        if (data.isEmpty()) return ReportOutcome.Notify(UiText.Res(StringKey.REPORT_NO_SPOTS))

        // ONE lookup for the whole month, not one per day: the logo is the
        // station's, and on desktop resolving it can mean a round trip.
        val config = logoCache.reportConfig()
        val payloads = data.map { it.toReportPayload(config) }

        val result = when (mode) {
            MonthReportMode.PREVIEW -> reportService.preview(payloads)
            MonthReportMode.PRINT -> reportService.print(payloads)
            MonthReportMode.EXPORT_PDF -> reportService.exportToPdf(payloads, pdfFileName(ctx))
        }
        return when (result) {
            // The engine's own text is authoritative - never translated.
            is ReportResult.Success -> ReportOutcome.Notify(
                UiText.Dynamic(
                    result.filePath?.let { path -> StringKey.REPORT_PDF_SAVED_PREFIX.localized() + path }
                        ?: result.message
                )
            )
            is ReportResult.Error -> ReportOutcome.Notify(UiText.Dynamic(result.message))
            ReportResult.Cancelled -> ReportOutcome.Notify(UiText.Res(StringKey.REPORT_CANCELLED))
        }
    }

    private fun pdfFileName(ctx: ReportContext): String =
        "ProgramFlow_${ctx.year}-${ctx.month.toString().padStart(2, '0')}.pdf"

    /**
     * The cells a report needs - the grid's aggregates with the airings
     * MERGED IN for just the slice being printed.
     *
     * The grid's own cells carry no airings (it draws counts; a month's worth
     * was 7.79 MB - see ScheduleRepository.getCommercials), so a report
     * fetches its own slice: one day, one break, one break across the month,
     * or the whole month. A failure comes back as [Slice.Failed] - the
     * caller turns it into the one snackbar.
     */
    private suspend fun cellsWithCommercials(
        ctx: ReportContext,
        date: LocalDate? = null,
        time: LocalTime? = null,
    ): Slice = when (val result = scheduleRepository.getCommercials(ctx.year, ctx.month, date, time)) {
        is DataResult.Success -> {
            val fetched = result.data
            Slice.Ready(
                ctx.cells.mapValues { (key, cell) ->
                    val coms = fetched[key.time to key.date]
                    if (coms == null) cell
                    else cell.copy(commercials = coms.map { it.toUi() }.toImmutableList())
                }.toImmutableMap()
            )
        }
        is DataResult.Failure -> Slice.Failed(result.error.toUiText())
    }
}

/**
 * The fetched slice or the reason there is none. An explicit type rather
 * than "null plus a lastError field": the controller is a per-report
 * factory, and a mutable field for a value read one line later invites the
 * question of who else can see it.
 */
private sealed interface Slice {
    data class Ready(val cells: ImmutableMap<SchedulerKey, SchedulerCellData>) : Slice
    data class Failed(val error: UiText) : Slice
}
