package eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.grids.CommercialItem
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommon
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
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
    val commercials: ImmutableList<CommercialItem> = persistentListOf(),
    /** The programme airing at this break (first placement's), when it has one. */
    val programName: String? = null,
    /** The previous/next OCCUPIED break of the day (legacy Break Console paging). */
    val previousBreak: BreakRef? = null,
    val nextBreak: BreakRef? = null,
)

sealed interface CommercialDetailIntent {
    /** The cell's placement ids in the new display order. */
    data class Reorder(val orderedIds: List<Long>) : CommercialDetailIntent
}

/**
 * The detail screen's own ViewModel - a thin delegate: its cell's
 * commercials are OBSERVED through the flow-shared [TimetableCommon]
 * contract (the same truth the grid renders) and the reorder command is
 * DELEGATED back through it (optimistic apply + persist + error policy
 * live in the CommonViewModel, once).
 *
 * Προηγούμενο/Επόμενο (the legacy Break Console's paging) walks the day's
 * OCCUPIED breaks in air order: the station grid comes from the repository
 * once, the occupancy live from the shared cells - so the neighbours stay
 * correct as placements change.
 */
@Stable
class CommercialDetailViewModel(
    private val breakId: Long,
    private val date: LocalDate,
    private val common: TimetableCommon,
    private val scheduleRepository: ScheduleRepository,
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

    val state by combine(common.commonState, breakSlots) { commonState, slots ->
        val cells = commonState.cells
        val day = slots
            .sortedBy { it.hour * 60 + it.minute }
            .mapNotNull { slot ->
                val spots = cells[SchedulerKey(slot.id, date)]?.spotCount ?: 0
                // the day's paging chain: occupied breaks + the one being viewed
                if (spots > 0 || slot.id == breakId) BreakRef(slot.id, slot.label, spots) else null
            }
        val at = day.indexOfFirst { it.breakId == breakId }
        CommercialDetailState(
            commercials = cells[key]?.commercials ?: persistentListOf(),
            programName = cells[key]?.programName,
            previousBreak = if (at > 0) day[at - 1] else null,
            nextBreak = if (at >= 0 && at < day.lastIndex) day[at + 1] else null,
        )
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            common.commonState.value.cells[key].toDetailState(),
        )
        .toComposeState(viewModelScope)

    fun onAction(intent: CommercialDetailIntent) {
        when (intent) {
            is CommercialDetailIntent.Reorder -> common.reorder(breakId, date, intent.orderedIds)
        }
    }
}

/** The cell's commercials and programme, or an empty state when the cell is gone. */
private fun SchedulerCellData?.toDetailState(): CommercialDetailState =
    CommercialDetailState(
        commercials = this?.commercials ?: persistentListOf(),
        programName = this?.programName,
    )
