package eu.anifantakis.commercials.feature.timetable.presentation.commercial_detail

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.util.toDisplayMessage
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.feature.timetable.presentation.store.ScheduleCellsStore
import eu.anifantakis.commercials.grids.CommercialItem
import eu.anifantakis.commercials.grids.SchedulerKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
 * The detail screen's own ViewModel. The cell's commercials come from the
 * shared [ScheduleCellsStore] (the same truth the grid renders); reorders
 * apply optimistically there and persist through the repository.
 */
class CommercialDetailViewModel(
    breakId: Long,
    date: LocalDate,
    private val placementsRepository: PlacementsRepository,
    private val store: ScheduleCellsStore,
) : BaseGlobalViewModel() {

    private val key = SchedulerKey(breakId, date)
    private val breakIdArg = breakId
    private val dateArg = date

    val state by store.state
        .map { CommercialDetailState(commercials = it.cells[key]?.commercials ?: persistentListOf()) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            CommercialDetailState(store.state.value.cells[key]?.commercials ?: persistentListOf()),
        )
        .toComposeState(viewModelScope)

    fun onAction(intent: CommercialDetailIntent) {
        when (intent) {
            is CommercialDetailIntent.Reorder -> {
                store.applyReorder(key, intent.orderedIds)
                viewModelScope.launch {
                    when (val result = placementsRepository.reorder(breakIdArg, dateArg, intent.orderedIds)) {
                        is DataResult.Success -> Unit
                        is DataResult.Failure -> showSnackbar(result.error.toDisplayMessage())
                    }
                }
            }
        }
    }
}
