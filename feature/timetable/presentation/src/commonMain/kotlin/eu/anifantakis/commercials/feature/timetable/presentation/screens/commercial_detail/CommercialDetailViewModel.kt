package eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.presentation.grids.CommercialItem
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommonViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

@Immutable
data class CommercialDetailState(
    val commercials: ImmutableList<CommercialItem> = persistentListOf(),
)

sealed interface CommercialDetailIntent {
    /** The cell's placement ids in the new display order. */
    data class Reorder(val orderedIds: List<Long>) : CommercialDetailIntent
}

/**
 * The detail screen's own ViewModel - a thin delegate: its cell's
 * commercials are OBSERVED from the flow-shared [TimetableCommonViewModel]
 * (the same truth the grid renders) and the reorder command is DELEGATED
 * back to it (optimistic apply + persist + error policy live there, once).
 */
@Stable
class CommercialDetailViewModel(
    private val breakId: Long,
    private val date: LocalDate,
    private val common: TimetableCommonViewModel,
) : BaseGlobalViewModel() {

    private val key = SchedulerKey(breakId, date)

    val state by common.state
        .map { CommercialDetailState(commercials = it.cells[key]?.commercials ?: persistentListOf()) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            CommercialDetailState(common.state.value.cells[key]?.commercials ?: persistentListOf()),
        )
        .toComposeState(viewModelScope)

    fun onAction(intent: CommercialDetailIntent) {
        when (intent) {
            is CommercialDetailIntent.Reorder -> common.reorder(breakId, date, intent.orderedIds)
        }
    }
}
