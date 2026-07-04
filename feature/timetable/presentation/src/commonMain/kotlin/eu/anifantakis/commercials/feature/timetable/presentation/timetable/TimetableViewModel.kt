package eu.anifantakis.commercials.feature.timetable.presentation.timetable

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.party_search.PartySearchRepository
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.util.toDisplayMessage
import eu.anifantakis.commercials.feature.timetable.domain.FinderRepository
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.toUi
import eu.anifantakis.commercials.feature.timetable.presentation.store.ScheduleCellsStore
import eu.anifantakis.commercials.grids.BreakSlot
import eu.anifantakis.commercials.grids.SchedulerCellData
import eu.anifantakis.commercials.grids.SchedulerKey
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
    /** Cells this session touched - the classic black marker. */
    val modifiedCells: ImmutableSet<SchedulerKey> = persistentSetOf(),
    val selectedRow: Int = 0,
    val selectedColumn: Int = 0,
    val showFinder: Boolean = false,
    val finder: FinderUiState = FinderUiState(),
    /** How many session-added placements each cell holds ('r' enablement). */
    val addedCounts: ImmutableMap<SchedulerKey, Int> = persistentMapOf(),
    val editError: String? = null,
)

sealed interface TimetableIntent {
    data object PreviousMonth : TimetableIntent
    data object NextMonth : TimetableIntent

    /** Session changed (login/station switch) - reload with a clean slate. */
    data object Reload : TimetableIntent

    data class SelectionChanged(val row: Int, val column: Int) : TimetableIntent
    data class OpenCell(
        val breakId: Long,
        val breakLabel: String,
        val date: LocalDate,
        val spotCount: Int,
    ) : TimetableIntent

    data class AddSpotAt(val breakId: Long, val date: LocalDate) : TimetableIntent
    data class RemoveLastAt(val breakId: Long, val date: LocalDate) : TimetableIntent
    data class ReorderCell(val breakId: Long, val date: LocalDate, val orderedIds: List<Long>) : TimetableIntent

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
    data class OpenDetail(
        val breakId: Long,
        val breakLabel: String,
        val date: LocalDate,
        val spotCount: Int,
    ) : TimetableEffect
}

/**
 * The timetable SCREEN's ViewModel (grid + its finder dialog). The cells
 * themselves live in the shared [ScheduleCellsStore] so the detail screen's
 * own ViewModel sees and edits the same truth - per-screen ViewModels,
 * shared state only where it is genuinely shared.
 */
class TimetableViewModel(
    private val scheduleRepository: ScheduleRepository,
    private val placementsRepository: PlacementsRepository,
    private val finderRepository: FinderRepository,
    private val partySearch: PartySearchRepository,
    private val store: ScheduleCellsStore,
) : BaseGlobalViewModel() {

    private val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    private val _state = MutableStateFlow(TimetableState(year = today.year, month = today.monthNumber))
    val state by _state
        .onStart { loadAll() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), _state.value)
        .toComposeState(viewModelScope)

    private val eventChannel = Channel<TimetableEffect>()
    val events = eventChannel.receiveAsFlow()

    private var searchJob: Job? = null

    init {
        // The store is the single truth for cells; mirror it into this
        // screen's state so the grid renders straight from TimetableState.
        viewModelScope.launch {
            store.state.collect { cs ->
                _state.update {
                    it.copy(cells = cs.cells, modifiedCells = cs.modifiedCells, addedCounts = cs.addedCounts)
                }
            }
        }
    }

    fun onAction(intent: TimetableIntent) {
        when (intent) {
            TimetableIntent.PreviousMonth -> changeMonth(-1)
            TimetableIntent.NextMonth -> changeMonth(+1)
            TimetableIntent.Reload -> {
                store.clear()
                _state.update {
                    TimetableState(year = it.year, month = it.month)
                }
                loadAll()
            }

            is TimetableIntent.SelectionChanged ->
                _state.update { it.copy(selectedRow = intent.row, selectedColumn = intent.column) }

            is TimetableIntent.OpenCell -> viewModelScope.launch {
                if (intent.spotCount > 0) {
                    eventChannel.send(
                        TimetableEffect.OpenDetail(intent.breakId, intent.breakLabel, intent.date, intent.spotCount)
                    )
                }
            }

            is TimetableIntent.AddSpotAt -> addSpotAt(intent.breakId, intent.date)
            is TimetableIntent.RemoveLastAt -> removeLastAt(intent.breakId, intent.date)
            is TimetableIntent.ReorderCell -> reorderCell(intent.breakId, intent.date, intent.orderedIds)

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
        viewModelScope.launch {
            when (val result = scheduleRepository.getBreaks()) {
                is DataResult.Success -> _state.update {
                    it.copy(breaks = result.data.map { b -> b.toUi() }.toImmutableList())
                }
                is DataResult.Failure -> _state.update { it.copy(breaks = persistentListOf()) }
            }
        }
        loadMonth()
    }

    private fun changeMonth(delta: Int) {
        val s = _state.value
        var year = s.year
        var month = s.month + delta
        if (month == 0) { month = 12; year-- }
        if (month == 13) { month = 1; year++ }
        store.clear()
        _state.update { it.copy(year = year, month = month, editError = null) }
        loadMonth()
    }

    private fun loadMonth() {
        val s = _state.value
        viewModelScope.launch {
            when (val result = scheduleRepository.getMonth(s.year, s.month)) {
                is DataResult.Success -> store.setMonthCells(result.data.cells.associate { c -> c.toUi() })
                is DataResult.Failure -> store.clear()
            }
        }
    }

    // ── placement editing ('a' / 'r' / detail reorder) ──────────────────

    private fun addSpotAt(breakId: Long, date: LocalDate) {
        val spot = _state.value.finder.selectedSpot ?: return   // 'a' is armed by the finder
        _state.update { it.copy(editError = null) }
        viewModelScope.launch {
            when (val result = placementsRepository.add(spot.spotId, breakId, date)) {
                is DataResult.Success -> store.applyAdd(SchedulerKey(breakId, date), result.data)
                is DataResult.Failure ->
                    _state.update { it.copy(editError = result.error.toDisplayMessage()) }
            }
        }
    }

    private fun removeLastAt(breakId: Long, date: LocalDate) {
        val key = SchedulerKey(breakId, date)
        val last = store.lastAddedIn(key) ?: return             // 'r' removes only our own adds
        _state.update { it.copy(editError = null) }
        viewModelScope.launch {
            when (val result = placementsRepository.remove(last.id)) {
                is DataResult.Success -> store.applyRemove(key, last)
                is DataResult.Failure ->
                    _state.update { it.copy(editError = result.error.toDisplayMessage()) }
            }
        }
    }

    private fun reorderCell(breakId: Long, date: LocalDate, orderedIds: List<Long>) {
        store.applyReorder(SchedulerKey(breakId, date), orderedIds)
        viewModelScope.launch {
            when (val result = placementsRepository.reorder(breakId, date, orderedIds)) {
                is DataResult.Success -> Unit
                is DataResult.Failure ->
                    _state.update { it.copy(editError = result.error.toDisplayMessage()) }
            }
        }
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
                is DataResult.Failure -> _state.update {
                    it.copy(
                        editError = result.error.toDisplayMessage(),
                        finder = it.finder.copy(searching = false),
                    )
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
                is DataResult.Failure -> _state.update {
                    it.copy(
                        editError = result.error.toDisplayMessage(),
                        finder = it.finder.copy(loadingLines = false),
                    )
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
                is DataResult.Failure -> _state.update {
                    it.copy(
                        editError = result.error.toDisplayMessage(),
                        finder = it.finder.copy(loadingSpots = false),
                    )
                }
            }
        }
    }
}
