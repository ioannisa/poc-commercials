package eu.anifantakis.commercials.feature.timetable.presentation.screens

import androidx.compose.runtime.Stable
import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.grids.BreakSlot
import eu.anifantakis.commercials.grids.SchedulerCellData
import eu.anifantakis.commercials.grids.SchedulerKey
import eu.anifantakis.commercials.core.presentation.global_state.BaseCommonViewModel
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
import eu.anifantakis.commercials.feature.timetable.domain.model.Program
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import eu.anifantakis.commercials.feature.timetable.domain.model.ScheduleFilter
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.toUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

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

    /**
     * The times of the operator's CLIENT-ONLY "add break" rows (createBreak
     * persists nothing). Tracked EXPLICITLY, because a refresh cannot infer
     * them from "rows the server did not return": under a «Προβολή Βάσει…»
     * filter the server legitimately returns fewer rows, and inferring would
     * resurrect every one the filter hides. Cleared with the month.
     */
    private val clientRowTimes = mutableSetOf<LocalTime>()

    // ── contract verbs: enqueue only - execution is serialized intents ─────

    override fun clear() = dispatch(TimetableCommonIntent.Clear)

    override fun loadMonth(year: Int, month: Int) =
        dispatch(TimetableCommonIntent.LoadMonth(year, month))

    override fun setViewMode(mode: GridViewMode) =
        dispatch(TimetableCommonIntent.SetViewMode(mode))

    override fun setFilter(filter: ScheduleFilter?) =
        dispatch(TimetableCommonIntent.SetFilter(filter))

    override fun loadCommercials(time: LocalTime, date: LocalDate) =
        dispatch(TimetableCommonIntent.LoadCommercials(time, date))

    override fun add(spotId: Long, time: LocalTime, date: LocalDate, programId: Long?) =
        dispatch(TimetableCommonIntent.Add(spotId, time, date, programId))

    override fun removeLast(time: LocalTime, date: LocalDate) =
        dispatch(TimetableCommonIntent.RemoveLast(time, date))

    override fun reorder(time: LocalTime, date: LocalDate, orderedIds: List<Long>) =
        dispatch(TimetableCommonIntent.Reorder(time, date, orderedIds))

    override fun createBreak(time: LocalTime, date: LocalDate) =
        dispatch(TimetableCommonIntent.CreateBreak(time, date))

    override fun refreshMonth() = dispatch(TimetableCommonIntent.RefreshMonth)

    override fun selectFinderParty(party: Party, kind: PartyKind) =
        dispatch(TimetableCommonIntent.SelectFinderParty(party, kind))

    override fun selectFinderLine(line: ContractLine) =
        dispatch(TimetableCommonIntent.SelectFinderLine(line))

    override fun setFinderSpots(spots: List<ContractLineSpot>) =
        dispatch(TimetableCommonIntent.SetFinderSpots(spots))

    override fun selectFinderSpot(spot: ContractLineSpot?) =
        dispatch(TimetableCommonIntent.SelectFinderSpot(spot))

    override fun clearFinder() = dispatch(TimetableCommonIntent.ClearFinder)

    override fun selectProgram(program: Program?) =
        dispatch(TimetableCommonIntent.SelectProgram(program))

    // ── the single reducer ──────────────────────────────────────────────────

    override suspend fun reduce(intent: TimetableCommonIntent) {
        when (intent) {
            TimetableCommonIntent.Clear -> {
                addedByCell.clear()
                clientRowTimes.clear()
                updateCommonState { TimetableCommonState() }
            }

            is TimetableCommonIntent.LoadMonth -> {
                // Blank the OLD month's cells AND rows before the await - the new
                // month's header must never sit above the previous month's
                // numbers, and its rows are the previous month's breaks. (The
                // rows used to survive here, back when they were a station-wide
                // grid; they are the month's own now.)
                addedByCell.clear()
                clientRowTimes.clear()
                val mode = commonState.value.viewMode
                val filter = commonState.value.filter
                // Blank the month's DATA; keep the operator's CHOICES.
                //
                // Written as copy-and-clear rather than building a fresh state
                // from a list of survivors, because the list-of-survivors form
                // is what broke it: it kept only viewMode/filter, so every
                // choice added later - the Εύρεση selection, then the Τύποι
                // Προγράμματος brush - was silently wiped on a month change,
                // disarming the 'a' key. This way a new choice field survives
                // by DEFAULT, and forgetting to blank a data field is the loud
                // error instead of the silent one.
                updateCommonState { st ->
                    st.copy(
                        year = intent.year,
                        month = intent.month,
                        breaks = persistentListOf(),
                        cells = persistentMapOf(),
                        modifiedCells = persistentSetOf(),
                        addedCounts = persistentMapOf(),
                    )
                }
                // ONE call for the whole grid. The rows and the cells came from two
                // endpoints fetched back-to-back, which made the screen wait for two
                // sequential round trips and had the server scan the same month
                // twice - the rows ARE the distinct times of the cells.
                when (val result = scheduleRepository.getMonth(intent.year, intent.month, mode, filter)) {
                    is DataResult.Success -> updateCommonState { st ->
                        st.copy(
                            breaks = result.data.rows.map { b -> b.toUi() }.toImmutableList(),
                            cells = result.data.cells.associate { c -> c.toUi() }.toImmutableMap(),
                        )
                    }
                    is DataResult.Failure -> {
                        updateCommonState { st -> st.copy(breaks = persistentListOf()) }
                        showSnackbar(result.error.toUiText())
                    }
                }
            }

            is TimetableCommonIntent.SetViewMode -> {
                val st = commonState.value
                if (st.viewMode == intent.mode) return
                updateCommonState { it.copy(viewMode = intent.mode) }
                // Only the ROWS change: the cells are the same airings whichever
                // view is on, so the month is not re-fetched. UNLESS a filter is
                // armed: /api/breaks knows nothing of it and would resurrect
                // every break the filter hides, so the filtered month (rows +
                // cells from one scan) is re-fetched instead.
                val year = st.year
                val month = st.month
                if (year != null && month != null) {
                    if (st.filter == null) loadRows(year, month, intent.mode)
                    else refreshMonthKeepingMarkers()
                }
            }

            is TimetableCommonIntent.SetFilter -> {
                if (commonState.value.filter == intent.filter) return
                updateCommonState { it.copy(filter = intent.filter) }
                // Same airings, different SCOPE: refresh in place (keeping the
                // session's markers and loaded airings) rather than blanking -
                // the operator is narrowing what they look at, not navigating.
                refreshMonthKeepingMarkers()
            }

            is TimetableCommonIntent.LoadCommercials -> {
                val st = commonState.value
                val year = st.year
                val month = st.month
                if (year == null || month == null) return
                val key = SchedulerKey(intent.time, intent.date)
                // Already have them (the cell was opened before, or a placement
                // was just added into it) - do not re-fetch.
                if (st.cells[key]?.commercials?.isNotEmpty() == true) return

                when (val result = scheduleRepository.getCommercials(year, month, intent.date, intent.time)) {
                    is DataResult.Success -> updateCommonState { s ->
                        val coms = result.data[intent.time to intent.date].orEmpty()
                        val cur = s.cells[key] ?: return@updateCommonState s
                        s.copy(
                            cells = (s.cells + (key to cur.copy(
                                commercials = coms.map { it.toUi() }.toImmutableList()
                            ))).toImmutableMap()
                        )
                    }
                    is DataResult.Failure -> showSnackbar(result.error.toUiText())
                }
            }

            is TimetableCommonIntent.Add -> {
                val key = SchedulerKey(intent.time, intent.date)
                val cell = commonState.value.cells[key]
                // WHITE going in: no cell, or a cell without a programme - this
                // add FOUNDS/PAINTS the break server-side, so its fresh paint
                // (the programme colour, which the optimistic apply cannot know)
                // must come back from the server.
                val wasWhite = cell == null || cell.programName == null
                when (val result =
                    placementsRepository.add(intent.spotId, intent.time, intent.date, intent.programId)) {
                    is DataResult.Success -> {
                        applyAdd(key, result.data)
                        if (wasWhite) refreshMonthKeepingMarkers()
                    }
                    is DataResult.Failure -> showSnackbar(result.error.toUiText())
                }
            }

            is TimetableCommonIntent.CreateBreak -> {
                // "Add new break" is a CLIENT-ONLY row - nothing is persisted. It
                // just gives the operator a white row to place the first spot
                // into; that first spot FOUNDS the real break (with a programme).
                // An unused row therefore vanishes on the next month load / app
                // restart - exactly the ephemerality the operator expects.
                clientRowTimes += intent.time
                updateCommonState { st ->
                    if (st.breaks.any { it.time == intent.time }) return@updateCommonState st
                    val label = "${intent.time.hour.toString().padStart(2, '0')}:" +
                        intent.time.minute.toString().padStart(2, '0')
                    st.copy(
                        breaks = (st.breaks + BreakSlot(time = intent.time, label = label))
                            .sortedBy { it.time }
                            .toImmutableList(),
                    )
                }
            }

            TimetableCommonIntent.RefreshMonth -> refreshMonthKeepingMarkers()

            is TimetableCommonIntent.RemoveLast -> {
                val key = SchedulerKey(intent.time, intent.date)
                val last = addedByCell[key]?.lastOrNull() ?: return
                when (val result = placementsRepository.remove(last.id)) {
                    is DataResult.Success -> applyRemove(key, last)
                    is DataResult.Failure -> showSnackbar(result.error.toUiText())
                }
            }

            // ── the Εύρεση selection: pure state, downstream resets ATOMIC ──
            // A narrower step resets everything beneath it, in the same reduce
            // tick - the finder window and the grid header can never observe a
            // spot that belongs to the previous line.

            is TimetableCommonIntent.SelectFinderParty -> updateCommonState {
                it.copy(finderSelection = FinderSelection(party = intent.party, kind = intent.kind))
            }

            is TimetableCommonIntent.SelectFinderLine -> updateCommonState {
                it.copy(
                    finderSelection = it.finderSelection.copy(
                        line = intent.line,
                        spot = null,
                        spots = persistentListOf(),
                    )
                )
            }

            is TimetableCommonIntent.SetFinderSpots -> updateCommonState {
                it.copy(finderSelection = it.finderSelection.copy(spots = intent.spots.toImmutableList()))
            }

            is TimetableCommonIntent.SelectFinderSpot -> updateCommonState {
                it.copy(finderSelection = it.finderSelection.copy(spot = intent.spot))
            }

            TimetableCommonIntent.ClearFinder -> updateCommonState {
                it.copy(finderSelection = FinderSelection())
            }

            is TimetableCommonIntent.SelectProgram -> updateCommonState {
                it.copy(armedProgram = intent.program)
            }

            is TimetableCommonIntent.Reorder -> {
                val key = SchedulerKey(intent.time, intent.date)
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
                when (val result = placementsRepository.reorder(intent.time, intent.date, intent.orderedIds)) {
                    is DataResult.Success -> Unit
                    is DataResult.Failure -> showSnackbar(result.error.toUiText())
                }
            }
        }
    }

    /**
     * Re-fetches the current month's rows + cells WITHOUT blanking first,
     * keeping the session's markers ([TimetableCommonState.modifiedCells] /
     * addedCounts) and any already-loaded airings. LoadMonth's blank-then-fetch
     * is right for navigation; this is for in-place server-side repaints (a
     * break founded/created, a programme recolored). Reducer-only, so it is
     * serialized with every other mutation.
     */
    private suspend fun refreshMonthKeepingMarkers() {
        val st = commonState.value
        val year = st.year ?: return
        val month = st.month ?: return
        when (val result = scheduleRepository.getMonth(year, month, st.viewMode, st.filter)) {
            is DataResult.Success -> updateCommonState { s ->
                val fresh = result.data.cells.associate { c -> c.toUi() }
                // The fresh cells carry no airings; keep the ones already loaded
                // (an opened Break Console, this session's optimistic adds).
                val merged = fresh.mapValues { (key, cell) ->
                    val old = s.cells[key]
                    if (old != null && old.commercials.isNotEmpty()) cell.copy(commercials = old.commercials)
                    else cell
                }
                val serverRows = result.data.rows.map { b -> b.toUi() }
                val serverTimes = serverRows.mapTo(mutableSetOf()) { it.time }
                // Keep the operator's CLIENT-ONLY "add break" rows across a
                // refetch: a time the operator added that the server still does
                // not know (no spot placed) and that holds no cell. Filling ONE
                // transient row must not drop the others. Only the EXPLICITLY
                // tracked times qualify - "rows the server did not return" also
                // describes every row a «Προβολή Βάσει…» filter hides, and
                // those must go. (A month change goes through loadMonth, which
                // blanks - so they vanish there.)
                val transient = s.breaks.filter { row ->
                    row.time in clientRowTimes &&
                        row.time !in serverTimes && merged.keys.none { it.time == row.time }
                }
                s.copy(
                    breaks = (serverRows + transient).sortedBy { it.time }.toImmutableList(),
                    cells = merged.toImmutableMap(),
                )
            }
            is DataResult.Failure -> showSnackbar(result.error.toUiText())
        }
    }

    /**
     * The month's ROWS in the current view. Called from inside the reducer, so
     * it is already serialized with everything else that touches the state.
     */
    private suspend fun loadRows(year: Int, month: Int, mode: GridViewMode) {
        when (val result = scheduleRepository.getBreaks(year, month, mode)) {
            is DataResult.Success -> updateCommonState { st ->
                st.copy(breaks = result.data.map { b -> b.toUi() }.toImmutableList())
            }
            is DataResult.Failure -> {
                updateCommonState { st -> st.copy(breaks = persistentListOf()) }
                showSnackbar(result.error.toUiText())
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
