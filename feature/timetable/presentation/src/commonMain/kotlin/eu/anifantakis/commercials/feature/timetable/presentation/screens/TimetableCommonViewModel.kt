package eu.anifantakis.commercials.feature.timetable.presentation.screens

import androidx.compose.runtime.Stable
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.core.presentation.global_state.BaseCommonViewModel
import eu.anifantakis.commercials.core.presentation.util.toDisplayMessage
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.toUi
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.datetime.LocalDate

/**
 * The timetable flow's shared owner (kmp-developer `<Feature>CommonViewModel`):
 * the month's cells + ALL placement I/O - optimistic apply + persist + ONE
 * error policy (global snackbar). The kernel serializes every mutation:
 * grid 'a'/'r' presses and detail reorders can never interleave a
 * read-modify-write on the cells.
 *
 * Pure state-sharing flow: no CommonEffect type ([Nothing]) - the reducer
 * only updates [commonState]; the grid and detail screens observe it
 * through the [TimetableCommon] contract. Owned by the TimetableFlow parent
 * entry (see NavigationTimetable) - popping the flow clears everything.
 */
@Stable
class TimetableCommonViewModel(
    private val scheduleRepository: ScheduleRepository,
    private val placementsRepository: PlacementsRepository,
) : BaseCommonViewModel<TimetableCommonState, TimetableCommonIntent, Nothing>(
    initialState = TimetableCommonState(),
), TimetableCommon {

    /** Session-added placements per cell, newest last - what 'r' removes. */
    private val addedByCell = mutableMapOf<SchedulerKey, MutableList<PlacedCommercial>>()

    // ── contract verbs: enqueue only - execution is serialized intents ─────

    override fun clear() = dispatch(TimetableCommonIntent.Clear)

    override fun loadMonth(year: Int, month: Int) =
        dispatch(TimetableCommonIntent.LoadMonth(year, month))

    override fun add(spotId: Long, breakId: Long, date: LocalDate) =
        dispatch(TimetableCommonIntent.Add(spotId, breakId, date))

    override fun removeLast(breakId: Long, date: LocalDate) =
        dispatch(TimetableCommonIntent.RemoveLast(breakId, date))

    override fun reorder(breakId: Long, date: LocalDate, orderedIds: List<Long>) =
        dispatch(TimetableCommonIntent.Reorder(breakId, date, orderedIds))

    // ── the single reducer ──────────────────────────────────────────────────

    override suspend fun reduce(intent: TimetableCommonIntent) {
        when (intent) {
            TimetableCommonIntent.Clear -> {
                addedByCell.clear()
                updateCommonState { TimetableCommonState() }
            }

            is TimetableCommonIntent.LoadMonth -> {
                when (val result = scheduleRepository.getMonth(intent.year, intent.month)) {
                    is DataResult.Success -> {
                        addedByCell.clear()
                        updateCommonState {
                            TimetableCommonState(
                                cells = result.data.cells.associate { c -> c.toUi() }.toImmutableMap()
                            )
                        }
                    }
                    is DataResult.Failure -> {
                        addedByCell.clear()
                        updateCommonState { TimetableCommonState() }
                        showSnackbar(result.error.toDisplayMessage())
                    }
                }
            }

            is TimetableCommonIntent.Add -> {
                when (val result = placementsRepository.add(intent.spotId, intent.breakId, intent.date)) {
                    is DataResult.Success -> applyAdd(SchedulerKey(intent.breakId, intent.date), result.data)
                    is DataResult.Failure -> showSnackbar(result.error.toDisplayMessage())
                }
            }

            is TimetableCommonIntent.RemoveLast -> {
                val key = SchedulerKey(intent.breakId, intent.date)
                val last = addedByCell[key]?.lastOrNull() ?: return
                when (val result = placementsRepository.remove(last.id)) {
                    is DataResult.Success -> applyRemove(key, last)
                    is DataResult.Failure -> showSnackbar(result.error.toDisplayMessage())
                }
            }

            is TimetableCommonIntent.Reorder -> {
                val key = SchedulerKey(intent.breakId, intent.date)
                // Optimistic apply first; the persist below is awaited inside
                // the reducer, so overlapping reorders stay ordered.
                updateCommonState { st ->
                    val cur = st.cells[key] ?: return@updateCommonState st
                    val byId = cur.commercials.associateBy { it.id }
                    val reordered = intent.orderedIds.mapNotNull { byId[it] }
                    if (reordered.size != cur.commercials.size) return@updateCommonState st
                    st.copy(
                        cells = (st.cells + (key to cur.copy(commercials = reordered.toImmutableList()))).toImmutableMap(),
                        modifiedCells = (st.modifiedCells + key).toImmutableSet(),
                    )
                }
                when (val result = placementsRepository.reorder(intent.breakId, intent.date, intent.orderedIds)) {
                    is DataResult.Success -> Unit
                    is DataResult.Failure -> showSnackbar(result.error.toDisplayMessage())
                }
            }
        }
    }

    private fun applyAdd(key: SchedulerKey, added: PlacedCommercial) {
        addedByCell.getOrPut(key) { mutableListOf() }.add(added)
        updateCommonState { st ->
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
        updateCommonState { st ->
            val cur = st.cells[key] ?: return@updateCommonState st
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
