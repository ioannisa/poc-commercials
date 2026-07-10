package eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.auth.UserSession
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.grids.CommercialItem
import eu.anifantakis.commercials.core.presentation.grids.FLOW_ROH
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommon
import eu.anifantakis.commercials.reports.ReportDataFactory
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.models.ReportConfig
import eu.anifantakis.commercials.reports.print
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
 * Προηγούμενο/Επόμενο (the legacy Break Console's paging) walks the day's
 * OCCUPIED breaks in air order: the station grid comes from the repository
 * once, the occupancy live from the shared cells - so the neighbours stay
 * correct as placements change.
 */
@Stable
class CommercialDetailViewModel(
    private val breakId: Long,
    private val breakLabel: String,
    private val date: LocalDate,
    private val common: TimetableCommon,
    private val scheduleRepository: ScheduleRepository,
    private val session: UserSession,
    private val reportService: ReportService,
) : BaseGlobalViewModel() {

    private val key = SchedulerKey(breakId, date)
    private val breakSlots = MutableStateFlow<List<BreakSlotInfo>>(emptyList())

    init {
        viewModelScope.launch {
            when (val result = scheduleRepository.getBreaks()) {
                is DataResult.Success -> breakSlots.value = result.data
                is DataResult.Failure -> Unit // paging stays disabled; the cell itself still renders
            }
        }
    }

    private val detailState = combine(common.commonState, breakSlots) { commonState, slots ->
        val cells = commonState.cells
        val day = slots
            .sortedBy { it.hour * 60 + it.minute }
            .mapNotNull { slot ->
                val spots = cells[SchedulerKey(slot.id, date)]?.spotCount ?: 0
                // the day's paging chain: occupied breaks + the one being viewed
                if (spots > 0 || slot.id == breakId) BreakRef(slot.id, slot.label, spots) else null
            }
        val at = day.indexOfFirst { it.breakId == breakId }
        buildState(
            cell = cells[key],
            previousBreak = if (at > 0) day[at - 1] else null,
            nextBreak = if (at >= 0 && at < day.lastIndex) day[at + 1] else null,
        )
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            buildState(common.commonState.value.cells[key]),
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
        val commercials = detailState.value.commercials
        if (commercials.isEmpty()) return
        viewModelScope.launch {
            val data = ReportDataFactory.createBreakProgramFlowData(
                date = date,
                breakTimeLabel = breakLabel,
                commercials = commercials,
            )
            if (data.items.isNotEmpty()) {
                reportService.print(data.toReportPayload(ReportConfig()))
            }
        }
    }

    /** The cell's commercials, programme and header stats - or an empty cell. */
    private fun buildState(
        cell: SchedulerCellData?,
        previousBreak: BreakRef? = null,
        nextBreak: BreakRef? = null,
    ): CommercialDetailState {
        val commercials = cell?.commercials ?: persistentListOf()
        val flow = commercials.filter { it.flow == FLOW_ROH }.toImmutableList()
        val totalDuration = commercials.sumOf { it.durationSeconds }
        val flowDuration = flow.sumOf { it.durationSeconds }
        return CommercialDetailState(
            date = date,
            breakLabel = breakLabel,
            commercials = commercials,
            programName = cell?.programName,
            previousBreak = previousBreak,
            nextBreak = nextBreak,
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
