package eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable

import eu.anifantakis.commercials.core.presentation.helper.UiText
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.party_search.PartySearchRepository
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.core.domain.auth.StationAccess
import eu.anifantakis.commercials.core.domain.refresh.DataRefreshBus
import eu.anifantakis.commercials.core.domain.auth.UserSession
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.timetable.domain.FinderRepository
import eu.anifantakis.commercials.feature.timetable.domain.TimetablePreferences
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommon
import eu.anifantakis.commercials.grids.BreakSlot
import eu.anifantakis.commercials.grids.SchedulerCellData
import eu.anifantakis.commercials.grids.SchedulerKey
import eu.anifantakis.commercials.grids.DailyStats
import eu.anifantakis.commercials.grids.StableDate
import eu.anifantakis.commercials.grids.formatTime
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.calculateDailyTotals
import eu.anifantakis.commercials.reports.ReportDataFactory
import eu.anifantakis.commercials.reports.ReportPayload
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.StationLogoCache
import eu.anifantakis.commercials.feature.timetable.presentation.screens.reportConfig
import eu.anifantakis.commercials.reports.models.ReportResult
import eu.anifantakis.commercials.reports.print
import eu.anifantakis.commercials.reports.toReportPayload
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.toUi

/** The Εύρεση console's state - part of the timetable (one workflow). */
@Immutable
data class FinderUiState(
    val kind: PartyKind = PartyKind.CUSTOMER,
    val query: String = "",
    val results: ImmutableList<Party> = persistentListOf(),
    val searching: Boolean = false,
    val selectedParty: Party? = null,
    /** The kind the selection was made under - toggling radios later must not reinterpret it. */
    val selectedKind: PartyKind = PartyKind.CUSTOMER,
    val lines: ImmutableList<ContractLine> = persistentListOf(),
    val loadingLines: Boolean = false,
    val selectedLine: ContractLine? = null,
    val spots: ImmutableList<ContractLineSpot> = persistentListOf(),
    val loadingSpots: Boolean = false,
    val selectedSpot: ContractLineSpot? = null,
)

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
    val cells: ImmutableMap<SchedulerKey, SchedulerCellData> = persistentMapOf(),
    /** The Σύνολα footer - derived from [cells], never in a composable. */
    val dailyTotals: ImmutableMap<StableDate, DailyStats> = persistentMapOf(),
    /** Cells this session touched - the classic black marker. */
    val modifiedCells: ImmutableSet<SchedulerKey> = persistentSetOf(),
    val selectedRow: Int = 0,
    val selectedColumn: Int = 0,
    /** Cells show spot COUNT or summed spot TIME (legacy popup option, persisted). */
    val showSpotTimes: Boolean = false,
    val showFinder: Boolean = false,
    val finder: FinderUiState = FinderUiState(),
    /** How many session-added placements each cell holds ('r' enablement). */
    val addedCounts: ImmutableMap<SchedulerKey, Int> = persistentMapOf(),

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

    data object OpenFinder : TimetableIntent
    data object CloseFinder : TimetableIntent
    data object ClearFinder : TimetableIntent
    data class FinderKindChanged(val kind: PartyKind) : TimetableIntent
    data class FinderQueryChanged(val query: String) : TimetableIntent
    data class FinderPartySelected(val party: Party) : TimetableIntent
    data class FinderLineSelected(val line: ContractLine) : TimetableIntent
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
 * The timetable SCREEN's ViewModel (grid + its finder dialog). The cells
 * live behind the flow-shared [TimetableCommon] CONTRACT: their slice of
 * state is observed and merged below, and every cell MUTATION is delegated
 * through its verbs (star topology - screen ViewModels never talk to each
 * other, and never see the concrete CommonViewModel).
 */
@Stable
class TimetableViewModel(
    private val finderRepository: FinderRepository,
    private val partySearch: PartySearchRepository,
    /**
     * Reports only. The grid reads the shared store; the PRINTED reports need the
     * airings, which the grid deliberately does not carry (see cellsWithCommercials),
     * so they fetch their own slice on demand.
     */
    private val scheduleRepository: ScheduleRepository,
    private val common: TimetableCommon,
    private val prefs: TimetablePreferences,
    private val session: UserSession,
    private val reportService: ReportService,
    private val logoCache: StationLogoCache,
    /**
     * The clock the grid opens on. Injectable ONLY so tests can pin it: the month
     * the screen starts on is read from here, and a test fixture pinned to a fixed
     * date silently stops matching it the moment the real month rolls over - a
     * suite that is green in July and red in August, with nothing having changed.
     */
    private val clock: Clock = Clock.System,
    /** Cross-feature "data changed" signal (AI-chat mutations); default = inert bus for tests. */
    private val refreshBus: DataRefreshBus = DataRefreshBus(),
) : BaseGlobalViewModel() {

    private val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    private val _state = MutableStateFlow(
        TimetableState(
            year = today.year,
            month = today.month.number,
            showSpotTimes = prefs.showSpotTimes,
            reportsAvailable = reportService.isReportGenerationAvailable(),
        ).withSessionFacts()
    )
    val state by _state
        .onStart { loadAll() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), _state.value)
        .toComposeState(viewModelScope)

    private val eventChannel = Channel<TimetableEffect>()
    val events = eventChannel.receiveAsFlow()

    private var searchJob: Job? = null

    init {
        // The common ViewModel is the single truth for the month's rows and
        // cells; mirror it into this screen's state so the grid renders straight
        // from TimetableState.
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
                    )
                }
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

            is TimetableIntent.PrintDay -> printDay(intent.date)
            is TimetableIntent.PrintBreak -> printBreak(intent.time, intent.date)
            is TimetableIntent.PrintBreakMonth -> printBreakMonth(intent.time)

            TimetableIntent.PreviewMonth -> runMonthReport { reportService.preview(it) }
            TimetableIntent.PrintMonth -> runMonthReport { reportService.print(it) }
            TimetableIntent.ExportMonthPdf -> {
                val s = _state.value
                val fileName = "ProgramFlow_${s.year}-${s.month.toString().padStart(2, '0')}.pdf"
                runMonthReport { reportService.exportToPdf(it, fileName) }
            }

            is TimetableIntent.SelectionChanged ->
                _state.update { it.copy(selectedRow = intent.row, selectedColumn = intent.column) }

            is TimetableIntent.ViewModeChanged -> common.setViewMode(intent.mode)

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

            TimetableIntent.OpenFinder -> _state.update { it.copy(showFinder = true) }
            TimetableIntent.CloseFinder -> _state.update { it.copy(showFinder = false) }
            TimetableIntent.ClearFinder -> _state.update { it.copy(finder = FinderUiState()) }

            is TimetableIntent.FinderKindChanged -> {
                if (_state.value.finder.kind != intent.kind) {
                    _state.update {
                        it.copy(finder = it.finder.copy(kind = intent.kind, results = persistentListOf()))
                    }
                    debouncedSearch()
                }
            }

            is TimetableIntent.FinderQueryChanged -> {
                _state.update { it.copy(finder = it.finder.copy(query = intent.query)) }
                debouncedSearch()
            }

            is TimetableIntent.FinderPartySelected -> selectParty(intent.party)
            is TimetableIntent.FinderLineSelected -> selectLine(intent.line)
            is TimetableIntent.FinderSpotSelected ->
                _state.update { it.copy(finder = it.finder.copy(selectedSpot = intent.spot)) }
        }
    }

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
     * The cells a report needs - the grid's aggregates with the airings MERGED IN
     * for just the slice being printed.
     *
     * The grid's own cells carry no airings (it draws counts; a month's worth was
     * 7.79 MB - see ScheduleRepository.getCommercials), so a report fetches its
     * own slice: one day, one break, one break across the month, or the whole
     * month. Returns null on a network failure, having already told the user.
     */
    private suspend fun cellsWithCommercials(
        date: LocalDate? = null,
        time: LocalTime? = null,
    ): ImmutableMap<SchedulerKey, SchedulerCellData>? {
        val s = _state.value
        return when (val result = scheduleRepository.getCommercials(s.year, s.month, date, time)) {
            is DataResult.Success -> {
                val fetched = result.data
                s.cells.mapValues { (key, cell) ->
                    val coms = fetched[key.time to key.date]
                    if (coms == null) cell
                    else cell.copy(commercials = coms.map { it.toUi() }.toImmutableList())
                }.toImmutableMap()
            }
            is DataResult.Failure -> {
                showSnackbar(result.error.toUiText())
                null
            }
        }
    }

    private fun printDay(date: LocalDate) {
        viewModelScope.launch {
            val cells = cellsWithCommercials(date = date) ?: return@launch
            val data = ReportDataFactory.createProgramFlowData(date, _state.value.breaks, cells)
            if (data.items.isNotEmpty()) reportService.print(data.toReportPayload(logoCache.reportConfig()))
        }
    }

    private fun printBreak(time: LocalTime, date: LocalDate) {
        val slot = _state.value.breaks.firstOrNull { it.time == time } ?: return
        viewModelScope.launch {
            val cells = cellsWithCommercials(date = date, time = time) ?: return@launch
            val cell = cells[SchedulerKey(time, date)] ?: return@launch
            val data = ReportDataFactory.createBreakProgramFlowData(
                date = date,
                breakTimeLabel = formatTime(slot.time.hour, slot.time.minute),
                commercials = cell.commercials,
                programName = cell.programName,
            )
            if (data.items.isNotEmpty()) reportService.print(data.toReportPayload(logoCache.reportConfig()))
        }
    }

    /**
     * The toolbar's three actions differ only in [action]: same month, same
     * payloads, same outcome policy. An empty month never reaches the service,
     * and every result surfaces through the app's ONE global snackbar - the
     * toolbar itself renders no messages.
     */
    private fun runMonthReport(action: suspend (List<ReportPayload>) -> ReportResult) {
        val s = _state.value
        if (s.reportBusy) return

        viewModelScope.launch {
            _state.update { it.copy(reportBusy = true) }
            try {
                // The whole month's airings - the ONE report that genuinely wants
                // them all, and it is an explicit user action, not a screen load.
                val cells = cellsWithCommercials() ?: return@launch
                val data = ReportDataFactory
                    .createMonthProgramFlowData(s.year, s.month, s.breaks, cells)
                if (data.isEmpty()) {
                    showSnackbar(StringKey.REPORT_NO_SPOTS)
                    return@launch
                }
                // ONE lookup for the whole month, not one per day: the logo is the
                // station's, and on desktop resolving it can mean a round trip.
                val config = logoCache.reportConfig()
                val payloads = data.map { it.toReportPayload(config) }
                when (val result = action(payloads)) {
                    // The engine's own text is authoritative - never translated.
                    is ReportResult.Success -> showSnackbar(
                        UiText.Dynamic(
                            result.filePath?.let { path -> StringKey.REPORT_PDF_SAVED_PREFIX.localized() + path }
                                ?: result.message
                        )
                    )
                    is ReportResult.Error -> showSnackbar(UiText.Dynamic(result.message))
                    ReportResult.Cancelled -> showSnackbar(StringKey.REPORT_CANCELLED)
                }
            } finally {
                // A save dialog can throw or be cancelled; the buttons must
                // never stay disabled because of it.
                _state.update { it.copy(reportBusy = false) }
            }
        }
    }

    private fun printBreakMonth(time: LocalTime) {
        val s = _state.value
        val slot = s.breaks.firstOrNull { it.time == time } ?: return
        viewModelScope.launch {
            // Just this break, across the month - not the month's every airing.
            val cells = cellsWithCommercials(time = time) ?: return@launch
            val config = logoCache.reportConfig()
            val payloads = ReportDataFactory
                .createMonthProgramFlowData(s.year, s.month, listOf(slot), cells)
                .map { it.toReportPayload(config) }
            if (payloads.isNotEmpty()) reportService.print(payloads)
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
        val spot = _state.value.finder.selectedSpot ?: return   // 'a' is armed by the finder
        common.add(spot.spotId, time, date)
    }

    // ── finder (Εύρεση console) ─────────────────────────────────────────

    /** 600ms after the last keystroke, min 3 chars - the legacy contract. */
    private fun debouncedSearch() {
        searchJob?.cancel()
        val query = _state.value.finder.query.trim()
        if (query.length < 3) {
            _state.update { it.copy(finder = it.finder.copy(results = persistentListOf(), searching = false)) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(600)
            _state.update { it.copy(finder = it.finder.copy(searching = true)) }
            when (val result = partySearch.search(query, _state.value.finder.kind)) {
                is DataResult.Success -> _state.update {
                    it.copy(finder = it.finder.copy(results = result.data.toImmutableList(), searching = false))
                }
                is DataResult.Failure -> {
                    showSnackbar(result.error.toUiText())
                    _state.update { it.copy(finder = it.finder.copy(searching = false)) }
                }
            }
        }
    }

    private fun selectParty(party: Party) {
        _state.update {
            it.copy(
                finder = it.finder.copy(
                    selectedParty = party,
                    selectedKind = it.finder.kind,
                    query = "",
                    results = persistentListOf(),
                    lines = persistentListOf(),
                    loadingLines = true,
                    selectedLine = null,
                    spots = persistentListOf(),
                    selectedSpot = null,
                )
            )
        }
        viewModelScope.launch {
            when (val result = finderRepository.contractLines(party.code, _state.value.finder.selectedKind)) {
                is DataResult.Success -> _state.update {
                    it.copy(finder = it.finder.copy(lines = result.data.toImmutableList(), loadingLines = false))
                }
                is DataResult.Failure -> {
                    showSnackbar(result.error.toUiText())
                    _state.update { it.copy(finder = it.finder.copy(loadingLines = false)) }
                }
            }
        }
    }

    private fun selectLine(line: ContractLine) {
        _state.update {
            it.copy(
                finder = it.finder.copy(
                    selectedLine = line,
                    spots = persistentListOf(),
                    loadingSpots = true,
                    selectedSpot = null,
                )
            )
        }
        viewModelScope.launch {
            when (val result = finderRepository.lineSpots(line.lineId)) {
                is DataResult.Success -> _state.update {
                    it.copy(finder = it.finder.copy(spots = result.data.toImmutableList(), loadingSpots = false))
                }
                is DataResult.Failure -> {
                    showSnackbar(result.error.toUiText())
                    _state.update { it.copy(finder = it.finder.copy(loadingSpots = false)) }
                }
            }
        }
    }
}
