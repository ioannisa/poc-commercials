package eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.auth.UserSession
import eu.anifantakis.commercials.core.presentation.grids.CommercialItem
import eu.anifantakis.commercials.core.presentation.grids.FLOW_ROH
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommon
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommonState
import eu.anifantakis.commercials.reports.ReportDataFactory
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.models.ReportConfig
import eu.anifantakis.commercials.reports.print
import eu.anifantakis.commercials.reports.toReportPayload
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** A neighbouring OCCUPIED break of the same day - the Προηγούμενο/Επόμενο target. */
@Immutable
data class BreakRef(
    val breakId: Long,
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
)

sealed interface CommercialDetailIntent {
    /**
     * Move the row at [from] to [to]. Every reorder gesture collapses here -
     * the up/down buttons, the Move submenu (top/bottom) and the row drag -
     * because they differ only in the indices the UI computed.
     */
    data class MoveRow(val from: Int, val to: Int) : CommercialDetailIntent

    /** Print this break's programme flow (the same report the grid popups print). */
    data object PrintBreak : CommercialDetailIntent
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
    private val breakId: Long,
    private val date: LocalDate,
    private val common: TimetableCommon,
    private val session: UserSession,
    private val reportService: ReportService,
) : BaseGlobalViewModel() {

    private val key = SchedulerKey(breakId, date)

    init {
        // Idempotent: the grid has almost certainly loaded them already, but the
        // screen states its own need rather than assuming the caller's order.
        common.loadBreaks()
    }

    private val detailState = common.commonState
        .map { commonState -> buildState(commonState) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            buildState(common.commonState.value),
        )

    val state by detailState.toComposeState(viewModelScope)

    fun onAction(intent: CommercialDetailIntent) {
        when (intent) {
            is CommercialDetailIntent.MoveRow -> moveRow(intent.from, intent.to)
            CommercialDetailIntent.PrintBreak -> printBreak()
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
        common.reorder(breakId, date, reordered.map { it.id })
    }

    private fun printBreak() {
        val current = detailState.value
        if (current.commercials.isEmpty()) return
        viewModelScope.launch {
            val data = ReportDataFactory.createBreakProgramFlowData(
                date = date,
                breakTimeLabel = current.breakLabel,
                commercials = current.commercials,
            )
            if (data.items.isNotEmpty()) {
                reportService.print(data.toReportPayload(ReportConfig()))
            }
        }
    }

    /**
     * Everything this screen shows, derived from the shared state alone: the
     * cell's commercials + programme, the header stats, this break's own label
     * and the day's paging chain. Only [breakId] and [date] came in from
     * navigation - the rest is PULLED, never pushed.
     */
    private fun buildState(commonState: TimetableCommonState): CommercialDetailState {
        val cells = commonState.cells

        // The day's paging chain: occupied breaks in air order, plus the one
        // being viewed (so an emptied break still knows its neighbours).
        val day = commonState.breaks
            .sortedBy { it.time.hour * 60 + it.time.minute }
            .mapNotNull { slot ->
                val spots = cells[SchedulerKey(slot.id, date)]?.spotCount ?: 0
                if (spots > 0 || slot.id == breakId) BreakRef(slot.id, slot.label, spots) else null
            }
        val at = day.indexOfFirst { it.breakId == breakId }

        val cell = cells[key]
        val commercials = cell?.commercials ?: persistentListOf()
        val flow = commercials.filter { it.flow == FLOW_ROH }.toImmutableList()
        val totalDuration = commercials.sumOf { it.durationSeconds }
        val flowDuration = flow.sumOf { it.durationSeconds }

        return CommercialDetailState(
            date = date,
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
