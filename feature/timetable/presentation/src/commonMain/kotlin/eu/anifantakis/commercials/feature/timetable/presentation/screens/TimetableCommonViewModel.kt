package eu.anifantakis.commercials.feature.timetable.presentation.screens

import eu.anifantakis.commercials.core.presentation.helper.UiText
import androidx.compose.runtime.Stable
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.core.presentation.global_state.BaseCommonViewModel
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.toUi
import kotlinx.collections.immutable.persistentListOf
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

    override fun loadBreaks() = dispatch(TimetableCommonIntent.LoadBreaks)

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

            TimetableCommonIntent.LoadBreaks -> {
                // Station-scoped and idempotent: the grid and the Break Console
                // both ask, and the reducer is serialized, so exactly one of
                // them reaches the network. [Clear] (station switch) re-arms it.
                if (commonState.value.breaks.isNotEmpty()) return
                when (val result = scheduleRepository.getBreaks()) {
                    is DataResult.Success -> updateCommonState { st ->
                        st.copy(breaks = result.data.map { b -> b.toUi() }.toImmutableList())
                    }
                    is DataResult.Failure -> {
                        updateCommonState { st -> st.copy(breaks = persistentListOf()) }
                        showSnackbar(result.error.toUiText())
                    }
                }
            }

            is TimetableCommonIntent.LoadMonth -> {
                // Blank the OLD month's cells before the await - the new month's
                // header must never sit above the previous month's numbers. The
                // breaks survive: they belong to the station, not the month.
                addedByCell.clear()
                updateCommonState { st -> TimetableCommonState(breaks = st.breaks) }
                when (val result = scheduleRepository.getMonth(intent.year, intent.month)) {
                    is DataResult.Success -> updateCommonState { st ->
                        st.copy(cells = result.data.cells.associate { c -> c.toUi() }.toImmutableMap())
                    }
                    is DataResult.Failure -> showSnackbar(result.error.toUiText())
                }
            }

            is TimetableCommonIntent.Add -> {
                when (val result = placementsRepository.add(intent.spotId, intent.breakId, intent.date)) {
                    is DataResult.Success -> applyAdd(SchedulerKey(intent.breakId, intent.date), result.data)
                    is DataResult.Failure -> showSnackbar(result.error.toUiText())
                }
            }

            is TimetableCommonIntent.RemoveLast -> {
                val key = SchedulerKey(intent.breakId, intent.date)
                val last = addedByCell[key]?.lastOrNull() ?: return
                when (val result = placementsRepository.remove(last.id)) {
                    is DataResult.Success -> applyRemove(key, last)
                    is DataResult.Failure -> showSnackbar(result.error.toUiText())
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
                    is DataResult.Failure -> showSnackbar(result.error.toUiText())
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
