package eu.anifantakis.commercials.feature.timetable.presentation.screens

import androidx.compose.runtime.Immutable
import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.grids.BreakSlot
import eu.anifantakis.commercials.grids.SchedulerCellData
import eu.anifantakis.commercials.grids.SchedulerKey
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
import eu.anifantakis.commercials.feature.timetable.domain.model.ScheduleFilter
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
     * Arms (or clears, with null) the "Προβολή Βάσει…" scope and re-fetches the
     * current month under it, KEEPING the session's markers. The filter then
     * rides every month load until changed - it is the operator's choice, like
     * the view mode. The cells' counts (and which cells exist at all) become
     * the filter's; a cell's AIRINGS stay unfiltered - opening a break always
     * shows its whole content.
     */
    fun setFilter(filter: ScheduleFilter?)

    /**
     * Loads ONE cell's airings and merges them into the store.
     *
     * The month grid does not carry airings (it draws counts and durations), so
     * whoever needs them asks. The Break Console does, on open - one cell, not
     * 13,009 of them.
     */
    fun loadCommercials(time: LocalTime, date: LocalDate)

    /**
     * Persists a placement of [spotId] into the (time, date) cell, then applies
     * it. [programId] (the operator's selected Τύπος Προγράμματος) matters only
     * when the cell is WHITE - no break there, or an unpainted one: the first
     * spot PAINTS the break with it and inherits it. A painted break ignores
     * it - the spot takes the BREAK's programme. A first spot into a white
     * cell also re-fetches the month, so the row and its fresh paint are
     * server truth.
     */
    fun add(spotId: Long, time: LocalTime, date: LocalDate, programId: Long? = null)

    /**
     * Removes the most recent placement THIS SESSION added in the cell
     * ('r' removes only our own adds); no-op when there is none.
     */
    fun removeLast(time: LocalTime, date: LocalDate)

    /** Optimistic reorder + persist (the server 409s on stale ids). */
    fun reorder(time: LocalTime, date: LocalDate, orderedIds: List<Long>)

    /**
     * Creates an EMPTY, UNPAINTED break at (time, date) - the legacy console's
     * "Πρόσθεση νέου διαλείμματος": it only holds a grid ROW, its cells stay
     * white - then re-fetches the month so the new row shows up.
     */
    fun createBreak(time: LocalTime, date: LocalDate)

    /**
     * Re-fetches the current month's rows + cells, KEEPING the session's
     * markers and already-loaded airings - unlike [loadMonth], which blanks
     * first. For server-side changes that repaint cells in place (a programme
     * recolored/renamed, a break created).
     */
    fun refreshMonth()

    // ── the Εύρεση selection ─────────────────────────────────────────────
    // Shared state by the membership test: the finder WINDOW mutates it, the
    // grid screen reads it (the Μηνύματα header, the 'a' key, the «Προβολή
    // Βάσει…» subjects). Each verb applies its downstream resets ATOMICALLY
    // in the serialized reducer - the window never read-modify-writes a
    // selection snapshot across its awaits.

    /** A new party: resets the line, the spot and the spot list with it. */
    fun selectFinderParty(party: Party, kind: PartyKind)

    /** A new contract line: resets the spot and the spot list. */
    fun selectFinderLine(line: ContractLine)

    /** The [selectFinderLine]'s spots, once fetched - the header dropdown reads them too. */
    fun setFinderSpots(spots: List<ContractLineSpot>)

    /** Arms (or, with null, disarms) the spot the grid's 'a' key places. */
    fun selectFinderSpot(spot: ContractLineSpot?)

    /** Drops the whole selection - the Μηνύματα X and the console's ΚΑΘΑΡΙΣΜΟΣ. */
    fun clearFinder()
}

/**
 * The Εύρεση selection the flow shares: what the finder window picked, what
 * the grid header displays, what 'a' places, what the armed «Προβολή Βάσει…»
 * modes read their subject from. The finder's SEARCH state (query, results,
 * contract lines, busy flags) is deliberately NOT here - only the window
 * renders it, so it stays in the window's own ViewModel.
 */
@Immutable
data class FinderSelection(
    val party: Party? = null,
    /** The kind [party] was selected under - later radio toggles must not reinterpret it. */
    val kind: PartyKind = PartyKind.CUSTOMER,
    val line: ContractLine? = null,
    /** The armed spot - what the grid's 'a' key places. */
    val spot: ContractLineSpot? = null,
    /** The [line]'s spots - the grid header's armed-spot dropdown switches among them. */
    val spots: ImmutableList<ContractLineSpot> = persistentListOf(),
)

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
    /** The armed "Προβολή Βάσει…" scope; survives month navigation like [viewMode]. */
    val filter: ScheduleFilter? = null,
    /** The month on screen - what [TimetableCommon.setViewMode] reloads the rows for. */
    val year: Int? = null,
    val month: Int? = null,
    val cells: ImmutableMap<SchedulerKey, SchedulerCellData> = persistentMapOf(),
    /** Cells this session touched - the classic black marker. */
    val modifiedCells: ImmutableSet<SchedulerKey> = persistentSetOf(),
    /** How many session-added placements each cell holds ('r' enablement). */
    val addedCounts: ImmutableMap<SchedulerKey, Int> = persistentMapOf(),
    /** The Εύρεση selection - see [FinderSelection]. */
    val finderSelection: FinderSelection = FinderSelection(),
)

/**
 * Internal plumbing: the contract's verbs enqueue these so the kernel's
 * single reducer executes all shared mutations in arrival order.
 */
sealed interface TimetableCommonIntent {
    data object Clear : TimetableCommonIntent
    data class LoadMonth(val year: Int, val month: Int) : TimetableCommonIntent
    data class SetViewMode(val mode: GridViewMode) : TimetableCommonIntent
    data class SetFilter(val filter: ScheduleFilter?) : TimetableCommonIntent
    data class LoadCommercials(val time: LocalTime, val date: LocalDate) : TimetableCommonIntent
    data class Add(
        val spotId: Long,
        val time: LocalTime,
        val date: LocalDate,
        val programId: Long?,
    ) : TimetableCommonIntent
    data class RemoveLast(val time: LocalTime, val date: LocalDate) : TimetableCommonIntent
    data class Reorder(val time: LocalTime, val date: LocalDate, val orderedIds: List<Long>) : TimetableCommonIntent
    data class CreateBreak(val time: LocalTime, val date: LocalDate) : TimetableCommonIntent
    data object RefreshMonth : TimetableCommonIntent
    data class SelectFinderParty(val party: Party, val kind: PartyKind) : TimetableCommonIntent
    data class SelectFinderLine(val line: ContractLine) : TimetableCommonIntent
    data class SetFinderSpots(val spots: List<ContractLineSpot>) : TimetableCommonIntent
    data class SelectFinderSpot(val spot: ContractLineSpot?) : TimetableCommonIntent
    data object ClearFinder : TimetableCommonIntent
}
