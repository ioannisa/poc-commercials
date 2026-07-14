package eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.auth.UserSession
import eu.anifantakis.commercials.grids.CommercialItem
import eu.anifantakis.commercials.grids.FLOW_ROH
import eu.anifantakis.commercials.grids.SchedulerKey
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommon
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommonState
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.reports.ReportDataFactory
import eu.anifantakis.commercials.reports.ReportPayload
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.StationLogoCache
import eu.anifantakis.commercials.feature.timetable.presentation.screens.reportConfig
import eu.anifantakis.commercials.reports.models.ReportResult
import eu.anifantakis.commercials.reports.toReportPayload
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** A neighbouring OCCUPIED break of the same day - the Προηγούμενο/Επόμενο target. */
@Immutable
data class BreakRef(
    val time: LocalTime,
    val label: String,
    val spotCount: Int,
)

@Immutable
data class CommercialDetailState(
    /** Nav arguments the header renders; the day/month NAMES are localized at
     *  the UI edge so a live language switch still recomposes the header. */
    val date: LocalDate,
    val breakLabel: String = "",
    val commercials: ImmutableList<CommercialItem> = persistentListOf(),
    /** The programme airing at this break (first placement's), when it has one. */
    val programName: String? = null,
    /** The previous/next OCCUPIED break of the day (legacy Break Console paging). */
    val previousBreak: BreakRef? = null,
    val nextBreak: BreakRef? = null,
    /** View-only roles browse and print; they never reorder. */
    val canEdit: Boolean = false,
    // Header stats - derived ONCE here, never recomputed in a composable.
    val totalSpots: Int = 0,
    val flowSpots: Int = 0,
    val excludedSpots: Int = 0,
    val totalDuration: Int = 0,
    val flowDuration: Int = 0,
    val excludedDuration: Int = 0,
    /** A report action is running - the toolbar waits for it. */
    val reportBusy: Boolean = false,
    /** This platform can generate reports at all (mobile now can too). */
    val reportsAvailable: Boolean = false,
)

sealed interface CommercialDetailIntent {
    /**
     * Move the row at [from] to [to]. Every reorder gesture collapses here -
     * the up/down buttons, the Move submenu (top/bottom) and the row drag -
     * because they differ only in the indices the UI computed.
     */
    data class MoveRow(val from: Int, val to: Int) : CommercialDetailIntent

    /** Preview this break's programme flow. */
    data object PreviewBreak : CommercialDetailIntent

    /** Print this break's programme flow (the same report the grid popups print). */
    data object PrintBreak : CommercialDetailIntent

    /** Export this break's programme flow as a PDF (native save dialog). */
    data object ExportBreakPdf : CommercialDetailIntent
}

/**
 * The detail screen's own ViewModel - a thin delegate: its cell's
 * commercials are OBSERVED through the flow-shared [TimetableCommon]
 * contract (the same truth the grid renders) and the reorder command is
 * DELEGATED back through it (optimistic apply + persist + error policy
 * live in the CommonViewModel, once). The screen therefore keeps NO local
 * copy of the list - the store is the single source of truth.
 *
 * Navigation hands it only the cell's IDENTITY (breakId + date, which is what
 * a [SchedulerKey] is). Everything else - this break's label, the day's
 * Προηγούμενο/Επόμενο chain, the occupancy - is read from the shared state,
 * so the neighbours stay correct as placements change and nothing has to be
 * threaded through the nav route.
 */
@Stable
class CommercialDetailViewModel(
    private val time: LocalTime,
    private val date: LocalDate,
    private val common: TimetableCommon,
    private val session: UserSession,
    private val reportService: ReportService,
    private val logoCache: StationLogoCache,
) : BaseGlobalViewModel() {

    private val key = SchedulerKey(time, date)

    // No break load here: the rows arrive WITH the month (a break is a time a
    // spot aired at), and this screen is only reachable from a loaded cell.
    //
    // The AIRINGS are a different matter - the grid deliberately does not carry
    // them (it draws counts, and a month's worth cost 7.79 MB). So the console
    // asks for its own cell's, and only its own. Idempotent: the store skips the
    // fetch if they are already there.
    init {
        common.loadCommercials(time, date)
    }

    /** The screen's state is DERIVED from the shared store, so the transient
     *  report-busy flag lives beside it and is combined in - never a second
     *  copy of the list. */
    private val reportBusy = MutableStateFlow(false)

    private val detailState = combine(common.commonState, reportBusy) { commonState, busy ->
        buildState(commonState).copy(reportBusy = busy)
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            buildState(common.commonState.value),
        )

    val state by detailState.toComposeState(viewModelScope)

    fun onAction(intent: CommercialDetailIntent) {
        when (intent) {
            is CommercialDetailIntent.MoveRow -> moveRow(intent.from, intent.to)
            CommercialDetailIntent.PreviewBreak -> runBreakReport { reportService.preview(it) }
            CommercialDetailIntent.PrintBreak -> runBreakReport { reportService.print(it) }
            CommercialDetailIntent.ExportBreakPdf -> {
                val s = detailState.value
                val fileName = "ProgramFlow_${s.date}_${s.breakLabel.replace(':', '-')}.pdf"
                runBreakReport { reportService.exportToPdf(it, fileName) }
            }
        }
    }

    /**
     * The one reorder primitive. The permission check and the bounds check live
     * HERE, not in the composable: the UI only reports "the user moved row 3
     * onto row 1" and this decides whether that is allowed and what the new
     * order is. The store applies it optimistically and persists it.
     */
    private fun moveRow(from: Int, to: Int) {
        if (!session.role.canEdit) return
        val current = detailState.value.commercials
        if (from == to || from !in current.indices || to !in current.indices) return

        val reordered = current.toMutableList().apply { add(to, removeAt(from)) }
        common.reorder(time, date, reordered.map { it.id })
    }

    /**
     * The one report primitive for this break - preview/print/export differ
     * only in the ACTION, so the payload, the empty check, the busy flag and
     * the outcome policy live here once (mirrors TimetableViewModel's
     * runMonthReport).
     */
    private fun runBreakReport(action: suspend (List<ReportPayload>) -> ReportResult) {
        val current = detailState.value
        if (current.reportBusy) return

        val data = ReportDataFactory.createBreakProgramFlowData(
            date = date,
            breakTimeLabel = current.breakLabel,
            commercials = current.commercials,
            programName = current.programName,
        )
        if (data.items.isEmpty()) {
            showSnackbar(StringKey.REPORT_NO_SPOTS)
            return
        }
        viewModelScope.launch {
            reportBusy.value = true
            try {
                val payloads = listOf(data.toReportPayload(logoCache.reportConfig()))
                when (val result = action(payloads)) {
                    // The engine's own text is authoritative - never translated.
                    is ReportResult.Success -> showSnackbar(
                        UiText.Dynamic(
                            result.filePath?.let { path -> StringKey.REPORT_PDF_SAVED_PREFIX.localized() + path }
                                ?: result.message
                        )
                    )
                    is ReportResult.Error -> showSnackbar(UiText.Dynamic(result.message))
                    ReportResult.Cancelled -> showSnackbar(StringKey.REPORT_CANCELLED)
                }
            } finally {
                // A save dialog can throw or be cancelled; the buttons must
                // never stay disabled because of it.
                reportBusy.value = false
            }
        }
    }

    /**
     * Everything this screen shows, derived from the shared state alone: the
     * cell's commercials + programme, the header stats, this break's own label
     * and the day's paging chain. Only [time] and [date] came in from
     * navigation - the rest is PULLED, never pushed.
     */
    private fun buildState(commonState: TimetableCommonState): CommercialDetailState {
        val cells = commonState.cells

        // The day's paging chain: occupied breaks in air order, plus the one
        // being viewed (so an emptied break still knows its neighbours).
        // The empty rows an hourly/half-hourly view prints are NOT breaks, so
        // they drop out here - the chain still pages through the day's occupied
        // breaks only, as the legacy console did.
        val day = commonState.breaks
            .sortedBy { it.time.hour * 60 + it.time.minute }
            .mapNotNull { slot ->
                val spots = cells[SchedulerKey(slot.time, date)]?.spotCount ?: 0
                if (spots > 0 || slot.time == time) BreakRef(slot.time, slot.label, spots) else null
            }
        val at = day.indexOfFirst { it.time == time }

        val cell = cells[key]
        val commercials = cell?.commercials ?: persistentListOf()
        val flow = commercials.filter { it.flow == FLOW_ROH }.toImmutableList()
        val totalDuration = commercials.sumOf { it.durationSeconds }
        val flowDuration = flow.sumOf { it.durationSeconds }

        return CommercialDetailState(
            date = date,
            reportsAvailable = reportService.isReportGenerationAvailable(),
            breakLabel = day.getOrNull(at)?.label ?: "",
            commercials = commercials,
            programName = cell?.programName,
            previousBreak = if (at > 0) day[at - 1] else null,
            nextBreak = if (at >= 0 && at < day.lastIndex) day[at + 1] else null,
            canEdit = session.role.canEdit,
            totalSpots = commercials.size,
            flowSpots = flow.size,
            excludedSpots = commercials.size - flow.size,
            totalDuration = totalDuration,
            flowDuration = flowDuration,
            excludedDuration = totalDuration - flowDuration,
        )
    }
}
