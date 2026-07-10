package eu.anifantakis.commercials.feature.timetable.presentation.screens

import androidx.compose.runtime.Immutable
import eu.anifantakis.commercials.core.presentation.grids.BreakSlot
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

/**
 * The timetable flow's shared CONTRACT (kmp-developer `<Feature>Common`):
 * immutable state plus a few domain verbs and nothing else. Screen
 * ViewModels depend on THIS - never on the concrete
 * [TimetableCommonViewModel] - so a screen physically cannot mutate shared
 * state or reach internals, and a tiny fake makes every screen ViewModel
 * testable in commonTest.
 */
interface TimetableCommon {

    val commonState: StateFlow<TimetableCommonState>

    /** Fresh slate: login or station switch (the breaks belong to a station). */
    fun clear()

    /**
     * Loads the station's airtime grid ONCE. Idempotent: callers that need the
     * breaks (the grid's rows, the detail's break label and its Προηγούμενο/
     * Επόμενο chain) may each ask, and only the first ask hits the network.
     */
    fun loadBreaks()

    /** Loads the month's cells (blanks the old ones first, keeps the breaks). */
    fun loadMonth(year: Int, month: Int)

    /** Persists a placement of [spotId] into the cell, then applies it. */
    fun add(spotId: Long, breakId: Long, date: LocalDate)

    /**
     * Removes the most recent placement THIS SESSION added in the cell
     * ('r' removes only our own adds); no-op when there is none.
     */
    fun removeLast(breakId: Long, date: LocalDate)

    /** Optimistic reorder + persist (the server 409s on stale ids). */
    fun reorder(breakId: Long, date: LocalDate, orderedIds: List<Long>)
}

/** Flow-wide state - the `Common` infix is mandatory (ownership is visible). */
@Immutable
data class TimetableCommonState(
    /**
     * The station's airtime grid, in air order. STATION-scoped (the cells
     * below are MONTH-scoped), so it survives month navigation and is fetched
     * once - both the grid and the Break Console read it from here.
     */
    val breaks: ImmutableList<BreakSlot> = persistentListOf(),
    val cells: ImmutableMap<SchedulerKey, SchedulerCellData> = persistentMapOf(),
    /** Cells this session touched - the classic black marker. */
    val modifiedCells: ImmutableSet<SchedulerKey> = persistentSetOf(),
    /** How many session-added placements each cell holds ('r' enablement). */
    val addedCounts: ImmutableMap<SchedulerKey, Int> = persistentMapOf(),
)

/**
 * Internal plumbing: the contract's verbs enqueue these so the kernel's
 * single reducer executes all shared mutations in arrival order.
 */
sealed interface TimetableCommonIntent {
    data object Clear : TimetableCommonIntent
    data object LoadBreaks : TimetableCommonIntent
    data class LoadMonth(val year: Int, val month: Int) : TimetableCommonIntent
    data class Add(val spotId: Long, val breakId: Long, val date: LocalDate) : TimetableCommonIntent
    data class RemoveLast(val breakId: Long, val date: LocalDate) : TimetableCommonIntent
    data class Reorder(val breakId: Long, val date: LocalDate, val orderedIds: List<Long>) : TimetableCommonIntent
}
