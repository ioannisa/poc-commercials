package eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.presentation.grids.CommercialItem
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommon
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

@Immutable
data class CommercialDetailState(
    val commercials: ImmutableList<CommercialItem> = persistentListOf(),
    /** The programme airing at this break (first placement's), when it has one. */
    val programName: String? = null,
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
 */
@Stable
class CommercialDetailViewModel(
    private val breakId: Long,
    private val date: LocalDate,
    private val common: TimetableCommon,
) : BaseGlobalViewModel() {

    private val key = SchedulerKey(breakId, date)

    val state by common.commonState
        .map { it.cells[key].toDetailState() }
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
