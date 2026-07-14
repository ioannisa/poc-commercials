package eu.anifantakis.commercials.feature.timetable.presentation.screens

import androidx.compose.runtime.Immutable
import eu.anifantakis.commercials.grids.BreakSlot
import eu.anifantakis.commercials.grids.SchedulerCellData
import eu.anifantakis.commercials.grids.SchedulerKey
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

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

    /** Fresh slate: login or station switch. */
    fun clear()

    /**
     * Loads the month: its CELLS and its ROWS, together.
     *
     * They are loaded together because they are the same fact. A break is a time
     * a spot aired at, so the rows a month has depend on what aired IN that
     * month - they are not a station-wide grid that outlives it. (This used to be
     * two calls, with the breaks fetched once and cached for the session.)
     */
    fun loadMonth(year: Int, month: Int)

    /**
     * Switches the view ("Προβολή κάθε: 1 Ώρα / Μισή Ώρα / Διάλειμμα") and
     * reloads the current month's rows. Only the ROWS change - the cells are the
     * same airings either way; the view decides how much empty scaffold is drawn
     * around them.
     */
    fun setViewMode(mode: GridViewMode)

    /**
     * Loads ONE cell's airings and merges them into the store.
     *
     * The month grid does not carry airings (it draws counts and durations), so
     * whoever needs them asks. The Break Console does, on open - one cell, not
     * 13,009 of them.
     */
    fun loadCommercials(time: LocalTime, date: LocalDate)

    /** Persists a placement of [spotId] into the (time, date) cell, then applies it. */
    fun add(spotId: Long, time: LocalTime, date: LocalDate)

    /**
     * Removes the most recent placement THIS SESSION added in the cell
     * ('r' removes only our own adds); no-op when there is none.
     */
    fun removeLast(time: LocalTime, date: LocalDate)

    /** Optimistic reorder + persist (the server 409s on stale ids). */
    fun reorder(time: LocalTime, date: LocalDate, orderedIds: List<Long>)
}

/** Flow-wide state - the `Common` infix is mandatory (ownership is visible). */
@Immutable
data class TimetableCommonState(
    /**
     * The grid's ROWS, in air order: the month's real breaks plus whatever empty
     * scaffold [viewMode] prints around them.
     *
     * MONTH-scoped, like the cells - which is the correction at the heart of
     * this: they used to be cached station-wide, on the belief that an airtime
     * grid was a property of the station. It is not. A break exists where a spot
     * aired, so a quiet month has fewer rows than a busy one, and 23:55 becomes a
     * row the moment somebody puts a spot there.
     */
    val breaks: ImmutableList<BreakSlot> = persistentListOf(),
    /** Which rows are drawn when empty. Survives month navigation - it is the operator's choice. */
    val viewMode: GridViewMode = GridViewMode.CONDENSED,
    /** The month on screen - what [TimetableCommon.setViewMode] reloads the rows for. */
    val year: Int? = null,
    val month: Int? = null,
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
    data class LoadMonth(val year: Int, val month: Int) : TimetableCommonIntent
    data class SetViewMode(val mode: GridViewMode) : TimetableCommonIntent
    data class LoadCommercials(val time: LocalTime, val date: LocalDate) : TimetableCommonIntent
    data class Add(val spotId: Long, val time: LocalTime, val date: LocalDate) : TimetableCommonIntent
    data class RemoveLast(val time: LocalTime, val date: LocalDate) : TimetableCommonIntent
    data class Reorder(val time: LocalTime, val date: LocalDate, val orderedIds: List<Long>) : TimetableCommonIntent
}
