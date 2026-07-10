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
import eu.anifantakis.commercials.core.domain.auth.UserSession
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.timetable.domain.FinderRepository
import eu.anifantakis.commercials.feature.timetable.domain.TimetablePreferences
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommon
import eu.anifantakis.commercials.core.presentation.grids.BreakSlot
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.core.presentation.grids.DailyStats
import eu.anifantakis.commercials.core.presentation.grids.StableDate
import eu.anifantakis.commercials.core.presentation.grids.formatTime
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.calculateDailyTotals
import eu.anifantakis.commercials.reports.ReportDataFactory
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.models.ReportConfig
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
import kotlinx.collections.immutable.toImmutableSet
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

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
    val breaks: ImmutableList<BreakSlot> = persistentListOf(),
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

    // ── session facts the chrome renders (mirrored from UserSession) ────────
    /** Only NORMAL_USER edits; viewer roles browse and print. */
    val canEdit: Boolean = false,
    val displayName: String = "",
    val isAdmin: Boolean = false,
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
    /** [spotCount] only decides WHETHER the cell opens; it never travels on. */
    data class OpenCell(
        val breakId: Long,
        val date: LocalDate,
        val spotCount: Int,
    ) : TimetableIntent

    // Printing: the popup gives the cell/row/column it was opened on; the
    // ViewModel owns the payload assembly and the report service.
    data class PrintDay(val date: LocalDate) : TimetableIntent
    data class PrintBreak(val breakId: Long, val date: LocalDate) : TimetableIntent
    data class PrintBreakMonth(val breakId: Long) : TimetableIntent

    data class AddSpotAt(val breakId: Long, val date: LocalDate) : TimetableIntent
    data class RemoveLastAt(val breakId: Long, val date: LocalDate) : TimetableIntent

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
    data class OpenDetail(val breakId: Long, val date: LocalDate) : TimetableEffect
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
    private val common: TimetableCommon,
    private val prefs: TimetablePreferences,
    private val session: UserSession,
    private val reportService: ReportService,
) : BaseGlobalViewModel() {

    private val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    private val _state = MutableStateFlow(
        TimetableState(year = today.year, month = today.monthNumber, showSpotTimes = prefs.showSpotTimes)
            .withSessionFacts()
    )
    val state by _state
        .onStart { loadAll() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), _state.value)
        .toComposeState(viewModelScope)

    private val eventChannel = Channel<TimetableEffect>()
    val events = eventChannel.receiveAsFlow()

    private var searchJob: Job? = null

    init {
        // The common ViewModel is the single truth for the station's breaks and
        // the month's cells; mirror it into this screen's state so the grid
        // renders straight from TimetableState.
        viewModelScope.launch {
            common.commonState.collect { cs ->
                _state.update {
                    it.copy(
                        breaks = cs.breaks,
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
                if (index > 0) reload()
            }
        }
    }

    /** The chrome renders these; they change only when [UserSession] does. */
    private fun TimetableState.withSessionFacts(): TimetableState = copy(
        canEdit = session.role.canEdit,
        displayName = session.displayName,
        isAdmin = session.isAdmin,
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
            is TimetableIntent.PrintBreak -> printBreak(intent.breakId, intent.date)
            is TimetableIntent.PrintBreakMonth -> printBreakMonth(intent.breakId)

            is TimetableIntent.SelectionChanged ->
                _state.update { it.copy(selectedRow = intent.row, selectedColumn = intent.column) }

            TimetableIntent.ToggleShowTimes -> {
                val newValue = !_state.value.showSpotTimes
                prefs.showSpotTimes = newValue
                _state.update { it.copy(showSpotTimes = newValue) }
            }

            is TimetableIntent.OpenCell -> viewModelScope.launch {
                if (intent.spotCount > 0) {
                    eventChannel.send(TimetableEffect.OpenDetail(intent.breakId, intent.date))
                }
            }

            is TimetableIntent.AddSpotAt -> addSpotAt(intent.breakId, intent.date)
            is TimetableIntent.RemoveLastAt -> common.removeLast(intent.breakId, intent.date)

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

    private fun loadAll() {
        common.loadBreaks()
        common.loadMonth(_state.value.year, _state.value.month)
    }

    /** A new session (or station): clean slate, keep the month the user was on. */
    private fun reload() {
        common.clear()
        _state.update {
            TimetableState(year = it.year, month = it.month, showSpotTimes = it.showSpotTimes)
                .withSessionFacts()
        }
        loadAll()
    }

    // ── printing (payload assembly + the report service live HERE) ───────

    private fun printDay(date: LocalDate) {
        val s = _state.value
        viewModelScope.launch {
            val data = ReportDataFactory.createProgramFlowData(date, s.breaks, s.cells)
            if (data.items.isNotEmpty()) reportService.print(data.toReportPayload(ReportConfig()))
        }
    }

    private fun printBreak(breakId: Long, date: LocalDate) {
        val s = _state.value
        val slot = s.breaks.firstOrNull { it.id == breakId } ?: return
        val commercials = s.cells[SchedulerKey(breakId, date)]?.commercials ?: return
        viewModelScope.launch {
            val data = ReportDataFactory.createBreakProgramFlowData(
                date = date,
                breakTimeLabel = formatTime(slot.time.hour, slot.time.minute),
                commercials = commercials,
            )
            if (data.items.isNotEmpty()) reportService.print(data.toReportPayload(ReportConfig()))
        }
    }

    private fun printBreakMonth(breakId: Long) {
        val s = _state.value
        val slot = s.breaks.firstOrNull { it.id == breakId } ?: return
        viewModelScope.launch {
            val payloads = ReportDataFactory
                .createMonthProgramFlowData(s.year, s.month, listOf(slot), s.cells)
                .map { it.toReportPayload(ReportConfig()) }
            if (payloads.isNotEmpty()) reportService.print(payloads)
        }
    }

    private fun changeMonth(delta: Int) {
        val s = _state.value
        var year = s.year
        var month = s.month + delta
        if (month == 0) { month = 12; year-- }
        if (month == 13) { month = 1; year++ }
        // No clear() here: loadMonth blanks the month's own cells and keeps the
        // station's breaks, so the grid does not lose its rows between months.
        _state.update { it.copy(year = year, month = month) }
        common.loadMonth(year, month)
    }

    // ── placement editing ('a' / 'r') - delegated to the common VM ──────

    private fun addSpotAt(breakId: Long, date: LocalDate) {
        val spot = _state.value.finder.selectedSpot ?: return   // 'a' is armed by the finder
        common.add(spot.spotId, breakId, date)
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
