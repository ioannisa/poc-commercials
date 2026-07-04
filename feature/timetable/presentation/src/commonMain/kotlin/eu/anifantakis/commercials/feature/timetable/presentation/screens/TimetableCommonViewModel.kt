package eu.anifantakis.commercials.feature.timetable.presentation.screens

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.util.toDisplayMessage
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.toUi
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

@Immutable
data class CellsState(
    val cells: ImmutableMap<SchedulerKey, SchedulerCellData> = persistentMapOf(),
    /** Cells this session touched - the classic black marker. */
    val modifiedCells: ImmutableSet<SchedulerKey> = persistentSetOf(),
    /** How many session-added placements each cell holds ('r' enablement). */
    val addedCounts: ImmutableMap<SchedulerKey, Int> = persistentMapOf(),
)

/**
 * The timetable flow's SHARED ViewModel (skill: <Feature>CommonViewModel,
 * additive to the per-screen ones - never their substitute). Owns the ONE
 * genuinely shared piece - the month's cells - and ALL placement I/O:
 * optimistic apply + persist + a single error policy (global snackbar).
 *
 * Communication is a star: state flows DOWN ([state], observed by the
 * screen ViewModels and merged into their own states); commands come UP
 * (screen ViewModels receive this instance via their constructors from the
 * nav entries and call the methods below). This ViewModel never knows the
 * per-screen ones exist. Lifetime: the timetable flow's ViewModelStoreOwner
 * (see NavigationRoot), shared by the grid and detail entries.
 */
@Stable
class TimetableCommonViewModel(
    private val scheduleRepository: ScheduleRepository,
    private val placementsRepository: PlacementsRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(CellsState())
    val state: StateFlow<CellsState> = _state.asStateFlow()

    /** Session-added placements per cell, newest last - what 'r' removes. */
    private val addedByCell = mutableMapOf<SchedulerKey, MutableList<PlacedCommercial>>()

    /** Fresh slate: login/station switch or month navigation. */
    fun clear() {
        addedByCell.clear()
        _state.update { CellsState() }
    }

    /** Loads the month's cells (replaces the current ones). */
    fun loadMonth(year: Int, month: Int) {
        viewModelScope.launch {
            when (val result = scheduleRepository.getMonth(year, month)) {
                is DataResult.Success -> {
                    addedByCell.clear()
                    _state.update {
                        CellsState(cells = result.data.cells.associate { c -> c.toUi() }.toImmutableMap())
                    }
                }
                is DataResult.Failure -> {
                    clear()
                    showSnackbar(result.error.toDisplayMessage())
                }
            }
        }
    }

    /** Persists a placement of [spotId] into the cell, then applies it. */
    fun add(spotId: Long, breakId: Long, date: LocalDate) {
        viewModelScope.launch {
            when (val result = placementsRepository.add(spotId, breakId, date)) {
                is DataResult.Success -> applyAdd(SchedulerKey(breakId, date), result.data)
                is DataResult.Failure -> showSnackbar(result.error.toDisplayMessage())
            }
        }
    }

    /**
     * Removes the most recent placement THIS SESSION added in the cell
     * ('r' removes only our own adds); no-op when there is none.
     */
    fun removeLast(breakId: Long, date: LocalDate) {
        val key = SchedulerKey(breakId, date)
        val last = addedByCell[key]?.lastOrNull() ?: return
        viewModelScope.launch {
            when (val result = placementsRepository.remove(last.id)) {
                is DataResult.Success -> applyRemove(key, last)
                is DataResult.Failure -> showSnackbar(result.error.toDisplayMessage())
            }
        }
    }

    /**
     * Optimistic reorder + persist; the server enforces consistency (409 on
     * stale ids) and the next month load resyncs.
     */
    fun reorder(breakId: Long, date: LocalDate, orderedIds: List<Long>) {
        val key = SchedulerKey(breakId, date)
        _state.update { st ->
            val cur = st.cells[key] ?: return@update st
            val byId = cur.commercials.associateBy { it.id }
            val reordered = orderedIds.mapNotNull { byId[it] }
            if (reordered.size != cur.commercials.size) return@update st
            st.copy(
                cells = (st.cells + (key to cur.copy(commercials = reordered.toImmutableList()))).toImmutableMap(),
                modifiedCells = (st.modifiedCells + key).toImmutableSet(),
            )
        }
        viewModelScope.launch {
            when (val result = placementsRepository.reorder(breakId, date, orderedIds)) {
                is DataResult.Success -> Unit
                is DataResult.Failure -> showSnackbar(result.error.toDisplayMessage())
            }
        }
    }

    private fun applyAdd(key: SchedulerKey, added: PlacedCommercial) {
        addedByCell.getOrPut(key) { mutableListOf() }.add(added)
        _state.update { st ->
            val cur = st.cells[key] ?: SchedulerCellData()
            val newCell = cur.copy(
                spotCount = cur.spotCount + 1,
                totalDurationSeconds = cur.totalDurationSeconds + added.durationSeconds,
                commercials = (cur.commercials + added.toUi()).toImmutableList(),
            )
            st.copy(
                cells = (st.cells + (key to newCell)).toImmutableMap(),
                modifiedCells = (st.modifiedCells + key).toImmutableSet(),
                addedCounts = (st.addedCounts + (key to (addedByCell[key]?.size ?: 0))).toImmutableMap(),
            )
        }
    }

    private fun applyRemove(key: SchedulerKey, removed: PlacedCommercial) {
        val stack = addedByCell[key] ?: return
        stack.remove(removed)
        _state.update { st ->
            val cur = st.cells[key] ?: return@update st
            val newCell = cur.copy(
                spotCount = (cur.spotCount - 1).coerceAtLeast(0),
                totalDurationSeconds = (cur.totalDurationSeconds - removed.durationSeconds).coerceAtLeast(0),
                commercials = cur.commercials.filterNot { c -> c.id == removed.id }.toImmutableList(),
            )
            st.copy(
                cells = (st.cells + (key to newCell)).toImmutableMap(),
                modifiedCells = if (stack.isEmpty()) (st.modifiedCells - key).toImmutableSet()
                                else st.modifiedCells,
                addedCounts = (st.addedCounts + (key to stack.size)).toImmutableMap(),
            )
        }
    }
}
