package eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.core.domain.auth.StationAccess
import eu.anifantakis.commercials.core.domain.context.ActiveScreenContext
import eu.anifantakis.commercials.core.domain.refresh.DataRefreshBus
import eu.anifantakis.commercials.core.domain.auth.UserSession
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.feature.timetable.domain.TimetablePreferences
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.domain.model.Program
import eu.anifantakis.commercials.feature.timetable.presentation.reports.MonthReportMode
import eu.anifantakis.commercials.feature.timetable.presentation.reports.ReportContext
import eu.anifantakis.commercials.feature.timetable.presentation.reports.ReportOutcome
import eu.anifantakis.commercials.feature.timetable.presentation.reports.ScheduleReportsController
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FinderSelection
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommon
import eu.anifantakis.commercials.grids.BreakSlot
import eu.anifantakis.commercials.grids.SchedulerCellData
import eu.anifantakis.commercials.grids.SchedulerKey
import eu.anifantakis.commercials.grids.DailyStats
import eu.anifantakis.commercials.grids.StableDate
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.calculateDailyTotals
import eu.anifantakis.commercials.reports.print
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
import eu.anifantakis.commercials.feature.timetable.domain.model.ScheduleFilter

@Immutable
data class TimetableState(
    val year: Int,
    val month: Int,
    /** The grid's rows: the month's real breaks + this view's empty scaffold. */
    val breaks: ImmutableList<BreakSlot> = persistentListOf(),
    /**
     * Which rows are drawn when empty - the legacy console's «Προβολή κάθε»
     * radio group.
     */
    val viewMode: GridViewMode = GridViewMode.CONDENSED,
    /** The «Προβολή Βάσει…» radio - which selection scopes the grid's counts. */
    val showBasedOn: ShowBasedOn = ShowBasedOn.ALL,
    val cells: ImmutableMap<SchedulerKey, SchedulerCellData> = persistentMapOf(),
    /** The Σύνολα footer - derived from [cells], never in a composable. */
    val dailyTotals: ImmutableMap<StableDate, DailyStats> = persistentMapOf(),
    /** Cells this session touched - the classic black marker. */
    val modifiedCells: ImmutableSet<SchedulerKey> = persistentSetOf(),
    val selectedRow: Int = 0,
    val selectedColumn: Int = 0,
    /** Cells show spot COUNT or summed spot TIME (legacy popup option, persisted). */
    val showSpotTimes: Boolean = false,
    /**
     * The Εύρεση selection, MIRRORED from the flow's common state - the
     * finder WINDOW owns picking it (see screens/spot_finder), this screen
     * renders it (the Μηνύματα header) and consumes it (the 'a' key, the
     * «Προβολή Βάσει…» subjects).
     */
    val finder: FinderSelection = FinderSelection(),
    /** How many session-added placements each cell holds ('r' enablement). */
    val addedCounts: ImmutableMap<SchedulerKey, Int> = persistentMapOf(),

    /**
     * The armed «Τύποι Προγράμματος» brush, MIRRORED from the flow - the
     * catalog WINDOW owns picking it (see screens/program_types); this screen
     * only consumes it (the 'a' key paints a white cell with it) and draws it
     * in the header readout, which is why the whole Program travels, not an id.
     */
    val armedProgram: Program? = null,
    /** The «Πρόσθεση νέου διαλείμματος» box's ΩΩ:ΛΛ field. */
    val newBreakTime: String = "",

    /** This platform can generate reports at all (desktop/browser yes, mobile no). */
    val reportsAvailable: Boolean = false,
    /** A month report is running - the toolbar waits for it. */
    val reportBusy: Boolean = false,

    // ── session facts the chrome renders (mirrored from UserSession) ────────
    /** Only NORMAL_USER edits; viewer roles browse and print. */
    val canEdit: Boolean = false,
    val displayName: String = "",
    val isAdmin: Boolean = false,
    /** At least one AI provider is configured server-side - show the chat launcher. */
    val aiChatEnabled: Boolean = false,
    val role: AppRole = AppRole.REPORT_VIEWER,
    val stations: ImmutableList<StationAccess> = persistentListOf(),
    val selectedStation: StationAccess? = null,
)

/**
 * The «Προβολή Βάσει…» radio group - WHOSE airings the grid counts. Each mode
 * (except ALL) reads its subject from a selection the operator makes elsewhere
 * in the header: PROGRAM from the «Τύποι Προγράμματος» dropdown, the rest from
 * the Εύρεση console (party / contract line / armed spot). A mode whose
 * subject is not selected yet filters nothing - it ARMS, and the filter lands
 * the moment the selection does.
 */
enum class ShowBasedOn { ALL, PROGRAM, CUSTOMER, CONTRACT, MESSAGE }

sealed interface TimetableIntent {
    data object PreviousMonth : TimetableIntent
    data object NextMonth : TimetableIntent

    /** Switches the whole app to that station (role and data follow). */
    data class SelectStation(val stationId: String) : TimetableIntent

    data class SelectionChanged(val row: Int, val column: Int) : TimetableIntent
    data object ToggleShowTimes : TimetableIntent

    /**
     * The «Προβολή κάθε» radio group: reloads the month's ROWS. The cells are
     * unaffected - the same airings are there whichever view is on.
     */
    data class ViewModeChanged(val mode: GridViewMode) : TimetableIntent
    /** The «Προβολή Βάσει…» radio group: re-scopes the month's COUNTS. */
    data class ShowBasedOnChanged(val mode: ShowBasedOn) : TimetableIntent
    /** [spotCount] only decides WHETHER the cell opens; it never travels on. */
    data class OpenCell(
        val time: LocalTime,
        val date: LocalDate,
        val spotCount: Int,
    ) : TimetableIntent

    // Printing: the popup gives the cell/row/column it was opened on; the
    // ViewModel owns the payload assembly and the report service.
    data class PrintDay(val date: LocalDate) : TimetableIntent
    data class PrintBreak(val time: LocalTime, val date: LocalDate) : TimetableIntent
    data class PrintBreakMonth(val time: LocalTime) : TimetableIntent

    // The report toolbar: the WHOLE visible month, three ways.
    data object PreviewMonth : TimetableIntent
    data object PrintMonth : TimetableIntent
    data object ExportMonthPdf : TimetableIntent

    data class AddSpotAt(val time: LocalTime, val date: LocalDate) : TimetableIntent
    data class RemoveLastAt(val time: LocalTime, val date: LocalDate) : TimetableIntent

    /**
     * Arms the SELECTED CELL's programme as the brush - "paint the next break
     * like this one". Delegated straight up; the catalog window is not
     * involved and need not even be open.
     */
    data class ArmCellProgram(val program: Program) : TimetableIntent

    /** The ΩΩ:ΛΛ field of the «Πρόσθεση νέου διαλείμματος» box. */
    data class NewBreakTimeChanged(val value: String) : TimetableIntent
    /**
     * Adds a ROW at the typed time (an unpainted break on the focused day
     * anchors it); no-op when the time already holds a row. Its cells stay
     * white - the first spot into one (with a selected programme) paints it.
     */
    data object AddBreak : TimetableIntent

    // The Εύρεση pieces that stayed on the GRID after the finder became its
    // own screen: the Μηνύματα header's X and its armed-spot dropdown. The
    // search/drill-down intents live in screens/spot_finder.
    data object ClearFinder : TimetableIntent
    data class FinderSpotSelected(val spot: ContractLineSpot?) : TimetableIntent
}

sealed interface TimetableEffect {
    /**
     * The cell IDENTITY, nothing more: the detail ViewModel pulls the label,
     * the commercials and the paging chain from the shared state itself.
     */
    data class OpenDetail(val time: LocalTime, val date: LocalDate) : TimetableEffect
}

/**
 * The timetable SCREEN's ViewModel (the grid and its header chrome; the
 * Εύρεση console is its own screen now - screens/spot_finder). The cells
 * AND the finder selection live behind the flow-shared [TimetableCommon]
 * CONTRACT: their slices are observed and merged below, and every mutation
 * is delegated through its verbs (star topology - screen ViewModels never
 * talk to each other, and never see the concrete CommonViewModel).
 */
@Stable
class TimetableViewModel(
    /**
     * Reports: assembly, the slice fetch and the report service all live in
     * the controller. This screen keeps only the busy flag and the ONE
     * snackbar policy - it decides WHEN a report runs, never HOW.
     */
    private val reports: ScheduleReportsController,
    private val common: TimetableCommon,
    private val prefs: TimetablePreferences,
    private val session: UserSession,
    /**
     * The clock the grid opens on. Injectable ONLY so tests can pin it: the month
     * the screen starts on is read from here, and a test fixture pinned to a fixed
     * date silently stops matching it the moment the real month rolls over - a
     * suite that is green in July and red in August, with nothing having changed.
     */
    private val clock: Clock = Clock.System,
    /** Cross-feature "data changed" signal (AI-chat mutations); default = inert bus for tests. */
    private val refreshBus: DataRefreshBus = DataRefreshBus(),
    /** "What am I looking at" publisher the AI chat samples; inert default for tests. */
    private val screenContext: ActiveScreenContext = ActiveScreenContext(),
) : BaseGlobalViewModel() {

    private val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    private val _state = MutableStateFlow(
        TimetableState(
            year = today.year,
            month = today.month.number,
            showSpotTimes = prefs.showSpotTimes,
            reportsAvailable = reports.isAvailable(),
        ).withSessionFacts()
    )
    val state by _state
        .onStart { loadAll() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), _state.value)
        .toComposeState(viewModelScope)

    private val eventChannel = Channel<TimetableEffect>()
    val events = eventChannel.receiveAsFlow()

    init {
        // The common ViewModel is the single truth for the month's rows and
        // cells - AND for the Εύρεση selection, which the finder WINDOW
        // mutates; mirror both into this screen's state so the grid renders
        // straight from TimetableState.
        viewModelScope.launch {
            common.commonState.collect { cs ->
                _state.update {
                    it.copy(
                        breaks = cs.breaks,
                        viewMode = cs.viewMode,
                        cells = cs.cells,
                        dailyTotals = calculateDailyTotals(cs.cells).toImmutableMap(),
                        modifiedCells = cs.modifiedCells,
                        addedCounts = cs.addedCounts,
                        finder = cs.finderSelection,
                        armedProgram = cs.armedProgram,
                    )
                }
                // The finder's selection may have just moved (or dropped) the
                // armed «Προβολή Βάσει…» mode's subject - re-derive. Guarded
                // by comparison (and setFilter self-guards too), so the tick
                // this very call raises converges instead of looping.
                val derived = effectiveFilter()
                if (derived != cs.filter) common.setFilter(derived)
            }
        }

        // The session's revision is a plain StateFlow, so this bridge needs no
        // recomposer (and is unit-testable). Index 0 is the value already
        // seeded above - only a real CHANGE (login, logout, station switch)
        // refetches with the new token and re-evaluates the role.
        viewModelScope.launch {
            session.revision.collectIndexed { index, _ ->
                _state.update { it.withSessionFacts() }
                // Only refetch for a change that still HAS a session. A logout /
                // 401 also bumps the revision, but reloading then would fire an
                // authenticated call with no token, 401, and (before clear() was
                // made idempotent) spin a feedback loop. The app routes to Login.
                if (index > 0 && session.isLoggedIn) reload()
            }
        }

        // Out-of-screen writers (the AI assistant's approved mutations)
        // announce data changes here - refetch so the user watches the
        // change land in the grid live.
        viewModelScope.launch {
            refreshBus.events.collect { if (session.isLoggedIn) reload() }
        }

        // Publish "what the user is looking at" for the AI assistant: month,
        // view mode, and the selected cell (break time + day). Cheap string
        // build on every state change, sampled once per chat request.
        viewModelScope.launch {
            _state.collect { s ->
                val cell = s.breaks.getOrNull(s.selectedRow)?.let { slot ->
                    val day = (s.selectedColumn + 1).toString().padStart(2, '0')
                    val month = s.month.toString().padStart(2, '0')
                    val hh = slot.time.hour.toString().padStart(2, '0')
                    val mm = slot.time.minute.toString().padStart(2, '0')
                    ", selected cell: break $hh:$mm on ${s.year}-$month-$day"
                }.orEmpty()
                screenContext.current =
                    "Timetable grid of station '${s.selectedStation?.id ?: "?"}': month ${s.year}-" +
                        s.month.toString().padStart(2, '0') +
                        ", view mode ${s.viewMode.name}$cell"
            }
        }
    }

    /** The chrome renders these; they change only when [UserSession] does. */
    private fun TimetableState.withSessionFacts(): TimetableState = copy(
        canEdit = session.role.canEdit,
        displayName = session.displayName,
        isAdmin = session.isAdmin,
        aiChatEnabled = session.aiChatProviders.isNotEmpty(),
        role = session.role,
        stations = session.stations.toImmutableList(),
        selectedStation = session.selectedStation,
    )

    fun onAction(intent: TimetableIntent) {
        when (intent) {
            TimetableIntent.PreviousMonth -> changeMonth(-1)
            TimetableIntent.NextMonth -> changeMonth(+1)

            // Only ASK the session to switch: the revision bump it raises is
            // what re-seeds the facts and reloads (one path, not two).
            is TimetableIntent.SelectStation -> session.selectStation(intent.stationId)

            is TimetableIntent.PrintDay -> report { reports.printDay(it, intent.date) }
            is TimetableIntent.PrintBreak -> report { reports.printBreak(it, intent.time, intent.date) }
            is TimetableIntent.PrintBreakMonth -> report { reports.printBreakAcrossMonth(it, intent.time) }

            TimetableIntent.PreviewMonth -> report { reports.runMonth(it, MonthReportMode.PREVIEW) }
            TimetableIntent.PrintMonth -> report { reports.runMonth(it, MonthReportMode.PRINT) }
            TimetableIntent.ExportMonthPdf -> report { reports.runMonth(it, MonthReportMode.EXPORT_PDF) }

            is TimetableIntent.SelectionChanged ->
                _state.update { it.copy(selectedRow = intent.row, selectedColumn = intent.column) }

            is TimetableIntent.ViewModeChanged -> common.setViewMode(intent.mode)

            is TimetableIntent.ShowBasedOnChanged -> {
                _state.update { it.copy(showBasedOn = intent.mode) }
                // A mode without its subject selected yet only ARMS: the grid
                // stays unfiltered, and the hint says where the subject comes
                // from. The filter lands when the selection does (syncFilter
                // runs again on every selection change).
                if (intent.mode != ShowBasedOn.ALL && effectiveFilter() == null) {
                    showSnackbar(
                        if (intent.mode == ShowBasedOn.PROGRAM) StringKey.TIMETABLE_SELECT_PROGRAM_FIRST
                        else StringKey.TIMETABLE_FILTER_NEEDS_FINDER
                    )
                }
                syncFilter()
            }

            TimetableIntent.ToggleShowTimes -> {
                val newValue = !_state.value.showSpotTimes
                prefs.showSpotTimes = newValue
                _state.update { it.copy(showSpotTimes = newValue) }
            }

            is TimetableIntent.OpenCell -> viewModelScope.launch {
                if (intent.spotCount > 0) {
                    eventChannel.send(TimetableEffect.OpenDetail(intent.time, intent.date))
                }
            }

            is TimetableIntent.AddSpotAt -> addSpotAt(intent.time, intent.date)
            is TimetableIntent.RemoveLastAt -> common.removeLast(intent.time, intent.date)

            is TimetableIntent.ArmCellProgram -> common.selectProgram(intent.program)

            is TimetableIntent.NewBreakTimeChanged ->
                _state.update { it.copy(newBreakTime = intent.value) }
            TimetableIntent.AddBreak -> addBreak()

            // Both delegate UP: the selection is shared state, and the filter
            // re-derivation rides the commonState tick these verbs raise.
            TimetableIntent.ClearFinder -> common.clearFinder()
            is TimetableIntent.FinderSpotSelected -> common.selectFinderSpot(intent.spot)
        }
    }

    // ── «Προβολή Βάσει…» (the Show based on… filter) ────────────────────

    /**
     * The armed mode's subject, read from the CURRENT selections - or null
     * when the mode is ALL or its subject is not selected (yet): an armed mode
     * without a subject shows everything rather than nothing.
     */
    private fun effectiveFilter(): ScheduleFilter? {
        val s = _state.value
        return when (s.showBasedOn) {
            ShowBasedOn.ALL -> null
            ShowBasedOn.PROGRAM -> s.armedProgram?.let { ScheduleFilter.ByProgram(it.id) }
            ShowBasedOn.CUSTOMER -> s.finder.party?.let {
                ScheduleFilter.ByParty(it.code, s.finder.kind)
            }
            ShowBasedOn.CONTRACT -> s.finder.line?.let { ScheduleFilter.ByContract(it.lineId) }
            ShowBasedOn.MESSAGE -> s.finder.spot?.let { ScheduleFilter.BySpot(it.spotId) }
        }
    }

    /**
     * Re-derives the filter and hands it to the common store, which refetches
     * only on an actual change - so this is safe to call after EVERY selection
     * tick that could move the filter's subject.
     */
    private fun syncFilter() = common.setFilter(effectiveFilter())

    // ── data loading ────────────────────────────────────────────────────

    // One call: the month's cells AND its rows. A break is a time a spot aired
    // at, so the rows are the month's - there is no station-wide grid to fetch
    // separately (loadBreaks() is gone).
    private fun loadAll() {
        common.loadMonth(_state.value.year, _state.value.month)
    }

    /** A new session (or station): clean slate, keep the month the user was on. */
    private fun reload() {
        common.clear()
        _state.update {
            TimetableState(
                year = it.year,
                month = it.month,
                showSpotTimes = it.showSpotTimes,
                // A platform capability, not session data - it does not reset.
                reportsAvailable = it.reportsAvailable,
            ).withSessionFacts()
        }
        loadAll()
    }

    // ── printing (payload assembly + the report service live HERE) ───────

    /**
     * Runs a report and surfaces its one message, if it has one.
     *
     * The busy flag stays HERE, not in the controller: it disables the
     * toolbar, so it is screen state - and its try/finally is load-bearing,
     * because a save dialog can throw or be cancelled and the buttons must
     * never stay disabled because of it.
     */
    private fun report(block: suspend (ReportContext) -> ReportOutcome) {
        if (_state.value.reportBusy) return
        viewModelScope.launch {
            _state.update { it.copy(reportBusy = true) }
            try {
                val s = _state.value
                val outcome = block(ReportContext(s.year, s.month, s.breaks, s.cells))
                if (outcome is ReportOutcome.Notify) showSnackbar(outcome.message)
            } finally {
                _state.update { it.copy(reportBusy = false) }
            }
        }
    }

    private fun changeMonth(delta: Int) {
        val s = _state.value
        var year = s.year
        var month = s.month + delta
        if (month == 0) { month = 12; year-- }
        if (month == 13) { month = 1; year++ }
        // No clear() here: loadMonth reloads the month's cells AND its rows. The
        // rows genuinely change with the month - a quiet month breaks at fewer
        // times than a busy one - so they are not carried across.
        _state.update { it.copy(year = year, month = month) }
        common.loadMonth(year, month)
    }

    // ── placement editing ('a' / 'r') - delegated to the common VM ──────

    private fun addSpotAt(time: LocalTime, date: LocalDate) {
        val s = _state.value
        val spot = s.finder.spot ?: return   // 'a' is armed by the finder
        // A WHITE cell - no break there, or an UNPAINTED one (a «Πρόσθεση νέου
        // διαλείμματος» row) - is painted by its FIRST spot, so the legacy rule
        // applies: pick a Τύπος Προγράμματος first. A painted cell ignores the
        // selection: the spot takes ITS programme.
        val cell = s.cells[SchedulerKey(time, date)]
        if ((cell == null || cell.programName == null) && s.armedProgram == null) {
            showSnackbar(StringKey.TIMETABLE_SELECT_PROGRAM_FIRST)
            return
        }
        common.add(spot.spotId, time, date, s.armedProgram?.id)
    }

    // ── the programme console (Τύποι Προγράμματος + Πρόσθεση διαλείμματος) ──

    private fun addBreak() {
        val s = _state.value
        val time = parseHhMmOrNull(s.newBreakTime)
        if (time == null) {
            showSnackbar(StringKey.TIMETABLE_ADD_BREAK_INVALID_TIME)
            return
        }
        // The button adds a ROW, nothing more - no programme needed (the first
        // spot into one of its white cells is what carries a programme in). A
        // time that already holds a row (scaffold or real) is a no-op.
        if (s.breaks.any { it.time == time }) {
            showSnackbar(StringKey.TIMETABLE_BREAK_EXISTS)
            return
        }
        common.createBreak(time, selectedDate())
        _state.update { it.copy(newBreakTime = "") }
    }

    /** The grid's focused day - where the row-anchoring unpainted break lands. */
    private fun selectedDate(): LocalDate {
        val s = _state.value
        val first = LocalDate(s.year, s.month, 1)
        val daysInMonth = first.daysUntil(first.plus(1, DateTimeUnit.MONTH))
        return LocalDate(s.year, s.month, (s.selectedColumn + 1).coerceIn(1, daysInMonth))
    }

    /**
     * ΩΩ:ΛΛ like the server's own parser (seconds rejected, not truncated),
     * plus the colon-less digits the field lets through: "2355" -> 23:55,
     * "955" -> 9:55 (the last two digits are always the minutes).
     */
    private fun parseHhMmOrNull(value: String): LocalTime? {
        val v = value.trim()
        val m = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(v)
            ?: Regex("""^(\d{1,2})(\d{2})$""").matchEntire(v)
            ?: return null
        val (h, min) = m.destructured
        val hh = h.toIntOrNull() ?: return null
        val mm = min.toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59) return null
        return LocalTime(hh, mm)
    }

}
