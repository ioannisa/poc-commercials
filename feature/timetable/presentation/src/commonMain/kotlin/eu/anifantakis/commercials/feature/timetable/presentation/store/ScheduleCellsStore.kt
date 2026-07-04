package eu.anifantakis.commercials.feature.timetable.presentation.store

import androidx.compose.runtime.Immutable
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.toUi
import eu.anifantakis.commercials.grids.SchedulerCellData
import eu.anifantakis.commercials.grids.SchedulerKey
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

@Immutable
data class CellsState(
    val cells: ImmutableMap<SchedulerKey, SchedulerCellData> = persistentMapOf(),
    /** Cells this session touched - the classic black marker. */
    val modifiedCells: ImmutableSet<SchedulerKey> = persistentSetOf(),
    /** How many session-added placements each cell holds ('r' enablement). */
    val addedCounts: ImmutableMap<SchedulerKey, Int> = persistentMapOf(),
)

/**
 * The ONE shared piece between the timetable screen and the commercial
 * detail screen: the month's cells. A narrow state holder (Koin single,
 * NOT a ViewModel) - each screen keeps its own ViewModel and both read and
 * mutate the same truth through this store. No I/O here; ViewModels call
 * the repositories and apply the confirmed results.
 */
class ScheduleCellsStore {

    private val _state = MutableStateFlow(CellsState())
    val state: StateFlow<CellsState> = _state.asStateFlow()

    /** Session-added placements per cell, newest last - what 'r' removes. */
    private val addedByCell = mutableMapOf<SchedulerKey, MutableList<PlacedCommercial>>()

    fun setMonthCells(cells: Map<SchedulerKey, SchedulerCellData>) {
        addedByCell.clear()
        _state.update { CellsState(cells = cells.toImmutableMap()) }
    }

    fun clear() {
        addedByCell.clear()
        _state.update { CellsState() }
    }

    /** Applies a server-confirmed add to the cell. */
    fun applyAdd(key: SchedulerKey, added: PlacedCommercial) {
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

    /** The most recent session-added placement in the cell, if any. */
    fun lastAddedIn(key: SchedulerKey): PlacedCommercial? = addedByCell[key]?.lastOrNull()

    /** Applies a server-confirmed remove of [removed] from the cell. */
    fun applyRemove(key: SchedulerKey, removed: PlacedCommercial) {
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

    /** Optimistic reorder; the server enforces consistency (409 on stale). */
    fun applyReorder(key: SchedulerKey, orderedIds: List<Long>) {
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
    }
}
