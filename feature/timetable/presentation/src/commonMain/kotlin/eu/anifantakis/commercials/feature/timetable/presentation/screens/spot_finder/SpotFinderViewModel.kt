package eu.anifantakis.commercials.feature.timetable.presentation.screens.spot_finder

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.party_search.PartySearchRepository
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.timetable.domain.FinderRepository
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FinderSelection
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommon
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Εύρεση console's own state: the SEARCH machinery (query, matches,
 * contract lines, busy flags), which only this window renders. The
 * SELECTION - what was picked - is not owned here: it lives in the flow's
 * [TimetableCommon] (the grid header, the 'a' key and the «Προβολή Βάσει…»
 * subjects read it too) and is merged in below as [selection].
 */
@Immutable
data class SpotFinderState(
    val kind: PartyKind = PartyKind.CUSTOMER,
    val query: String = "",
    val results: ImmutableList<Party> = persistentListOf(),
    val searching: Boolean = false,
    val lines: ImmutableList<ContractLine> = persistentListOf(),
    val loadingLines: Boolean = false,
    val loadingSpots: Boolean = false,
    /** Merged from the flow's common state - the single truth for what is picked. */
    val selection: FinderSelection = FinderSelection(),
)

sealed interface SpotFinderIntent {
    data class KindChanged(val kind: PartyKind) : SpotFinderIntent
    data class QueryChanged(val query: String) : SpotFinderIntent
    data class PartySelected(val party: Party) : SpotFinderIntent
    data class LineSelected(val line: ContractLine) : SpotFinderIntent
    data class SpotSelected(val spot: ContractLineSpot?) : SpotFinderIntent
    data object Clear : SpotFinderIntent
}

/**
 * The Εύρεση window's ViewModel. Hosted in a FLOATING WINDOW with its own
 * keyed ViewModel scope (AppWindowHost): minimizing the window keeps this
 * instance - and the typed query, the matches, the drill-down - alive;
 * closing it destroys exactly this, while the SELECTION survives in the
 * flow's common state, which is precisely the armed-'a'-key contract.
 *
 * Star topology: selection mutations go UP through [TimetableCommon]'s
 * finder verbs (each applies its downstream resets atomically in the
 * serialized reducer); the merged [SpotFinderState.selection] comes DOWN
 * with every common tick. This ViewModel never touches a snapshot of the
 * selection across an await.
 */
@Stable
class SpotFinderViewModel(
    private val finderRepository: FinderRepository,
    private val partySearch: PartySearchRepository,
    private val common: TimetableCommon,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(
        SpotFinderState(
            // Reopening the console mid-selection starts on the kind that
            // selection was made under, exactly like the legacy dialog did.
            kind = common.commonState.value.finderSelection.kind,
            selection = common.commonState.value.finderSelection,
        )
    )
    val state by _state
        .onStart {
            // A fresh window over a SURVIVING selection (close does not clear
            // it): re-fetch the picked party's lines so the drill-down shows
            // the rows the selection came from. The spots ride in the shared
            // selection already - no second fetch.
            _state.value.selection.party?.let { loadLines(it, _state.value.selection.kind) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), _state.value)
        .toComposeState(viewModelScope)

    private var searchJob: Job? = null

    init {
        // Merge the shared selection down - the window's tables highlight it,
        // and an external change (the header's X, the armed-spot dropdown)
        // shows up here without any side channel.
        viewModelScope.launch {
            common.commonState.collect { cs ->
                _state.update { local ->
                    val cleared = cs.finderSelection.party == null && local.selection.party != null
                    local.copy(
                        selection = cs.finderSelection,
                        // The selection was dropped from outside: the lines
                        // table belongs to a party that is no longer picked.
                        lines = if (cleared) persistentListOf() else local.lines,
                    )
                }
            }
        }
    }

    fun onAction(intent: SpotFinderIntent) {
        when (intent) {
            is SpotFinderIntent.KindChanged -> {
                if (_state.value.kind != intent.kind) {
                    _state.update { it.copy(kind = intent.kind, results = persistentListOf()) }
                    debouncedSearch()
                }
            }

            is SpotFinderIntent.QueryChanged -> {
                _state.update { it.copy(query = intent.query) }
                debouncedSearch()
            }

            is SpotFinderIntent.PartySelected -> selectParty(intent.party)
            is SpotFinderIntent.LineSelected -> selectLine(intent.line)
            is SpotFinderIntent.SpotSelected -> common.selectFinderSpot(intent.spot)

            SpotFinderIntent.Clear -> {
                searchJob?.cancel()
                _state.update {
                    it.copy(
                        query = "",
                        results = persistentListOf(),
                        searching = false,
                        lines = persistentListOf(),
                        loadingLines = false,
                        loadingSpots = false,
                    )
                }
                common.clearFinder()
            }
        }
    }

    /** 600ms after the last keystroke, min 3 chars - the legacy contract. */
    private fun debouncedSearch() {
        searchJob?.cancel()
        val query = _state.value.query.trim()
        if (query.length < 3) {
            _state.update { it.copy(results = persistentListOf(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(600)
            _state.update { it.copy(searching = true) }
            when (val result = partySearch.search(query, _state.value.kind)) {
                is DataResult.Success -> _state.update {
                    it.copy(results = result.data.toImmutableList(), searching = false)
                }
                is DataResult.Failure -> {
                    showSnackbar(result.error.toUiText())
                    _state.update { it.copy(searching = false) }
                }
            }
        }
    }

    private fun selectParty(party: Party) {
        val kind = _state.value.kind
        _state.update { it.copy(query = "", results = persistentListOf()) }
        // The atomic reset of line/spot/spots happens IN the reducer.
        common.selectFinderParty(party, kind)
        loadLines(party, kind)
    }

    private fun loadLines(party: Party, kind: PartyKind) {
        _state.update { it.copy(lines = persistentListOf(), loadingLines = true) }
        viewModelScope.launch {
            when (val result = finderRepository.contractLines(party.code, kind)) {
                is DataResult.Success -> _state.update {
                    it.copy(lines = result.data.toImmutableList(), loadingLines = false)
                }
                is DataResult.Failure -> {
                    showSnackbar(result.error.toUiText())
                    _state.update { it.copy(loadingLines = false) }
                }
            }
        }
    }

    private fun selectLine(line: ContractLine) {
        common.selectFinderLine(line)
        _state.update { it.copy(loadingSpots = true) }
        viewModelScope.launch {
            when (val result = finderRepository.lineSpots(line.lineId)) {
                is DataResult.Success -> {
                    // The spots are SHARED state (the header dropdown switches
                    // among them), so they land in the common store, not here.
                    common.setFinderSpots(result.data)
                    _state.update { it.copy(loadingSpots = false) }
                }
                is DataResult.Failure -> {
                    showSnackbar(result.error.toUiText())
                    _state.update { it.copy(loadingSpots = false) }
                }
            }
        }
    }
}
