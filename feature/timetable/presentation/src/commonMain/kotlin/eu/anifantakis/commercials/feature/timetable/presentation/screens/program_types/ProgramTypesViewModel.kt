package eu.anifantakis.commercials.feature.timetable.presentation.screens.program_types

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.timetable.domain.ProgramsRepository
import eu.anifantakis.commercials.feature.timetable.domain.model.Program
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommon
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which catalog dialog is open (one per legacy button), if any. */
enum class ProgramDialog { ADD, EDIT, REMOVE, COLOR }

/**
 * The console's own state: the CATALOG and which dialog is open. The armed
 * brush is NOT here - it belongs to the flow (the grid's 'a' key and the
 * «Προβολή Βάσει…» filter read it), so it arrives merged in [selectedId].
 */
@Immutable
data class ProgramTypesState(
    val programs: ImmutableList<Program> = persistentListOf(),
    val dialog: ProgramDialog? = null,
    /** Narrows [visible] as the operator types - purely local, never persisted. */
    val filter: String = "",
    /**
     * Merged from the flow's common state - the single truth for the brush.
     * Resolved against [programs] so a rename/recolour shows through even
     * before the flow's copy is refreshed.
     */
    val armedId: Long? = null,
) {
    val selected: Program? get() = programs.firstOrNull { it.id == armedId }

    /**
     * What the list draws. Case-insensitive contains, because the catalog
     * runs to a couple of hundred entries whose names carry the presenter
     * («FATSABOOK Μάντζιος Γιώργος») - the operator remembers either half.
     * The ARMED programme is always kept visible, even when it does not
     * match: a filter must never make the current brush look unset.
     */
    val visible: ImmutableList<Program>
        get() {
            val q = filter.trim()
            if (q.isEmpty()) return programs
            return programs
                .filter { it.name.contains(q, ignoreCase = true) || it.id == armedId }
                .toImmutableList()
        }
}

sealed interface ProgramTypesIntent {
    data class Select(val programId: Long) : ProgramTypesIntent
    data class FilterChanged(val value: String) : ProgramTypesIntent
    data class OpenDialog(val dialog: ProgramDialog) : ProgramTypesIntent
    data object CloseDialog : ProgramTypesIntent
    data class Create(val name: String, val colorArgb: Int?) : ProgramTypesIntent
    data class Rename(val name: String) : ProgramTypesIntent
    data class Recolor(val colorArgb: Int) : ProgramTypesIntent
    data object Remove : ProgramTypesIntent
}

/**
 * «Τύποι Προγράμματος» - the catalog console, its own screen in a floating
 * window with its own keyed ViewModel scope.
 *
 * Star topology: the CATALOG and the open dialog are screen-local; the armed
 * brush goes UP through [TimetableCommon.selectProgram] and comes back DOWN
 * merged, so the grid and this console can never disagree about what is
 * armed. A rename or a recolor also asks the flow to refresh the month -
 * cells render the programme's NAME and COLOUR, so they must follow.
 */
@Stable
class ProgramTypesViewModel(
    private val repository: ProgramsRepository,
    private val common: TimetableCommon,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(
        ProgramTypesState(armedId = common.commonState.value.armedProgram?.id)
    )
    val state by _state
        .onStart { load() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), _state.value)
        .toComposeState(viewModelScope)

    init {
        viewModelScope.launch {
            common.commonState.collect { cs ->
                _state.update { it.copy(armedId = cs.armedProgram?.id) }
            }
        }
    }

    fun onAction(intent: ProgramTypesIntent) {
        when (intent) {
            is ProgramTypesIntent.Select ->
                common.selectProgram(_state.value.programs.firstOrNull { it.id == intent.programId })

            is ProgramTypesIntent.FilterChanged -> _state.update { it.copy(filter = intent.value) }

            is ProgramTypesIntent.OpenDialog -> {
                // ΔΙΟΡΘ/ΑΦΑΙΡ/Χρώμα act ON the selection; ΠΡΟΣΘ never needs one.
                if (intent.dialog != ProgramDialog.ADD && _state.value.selected == null) {
                    showSnackbar(StringKey.TIMETABLE_SELECT_PROGRAM_FIRST)
                } else {
                    _state.update { it.copy(dialog = intent.dialog) }
                }
            }

            ProgramTypesIntent.CloseDialog -> _state.update { it.copy(dialog = null) }

            is ProgramTypesIntent.Create -> create(intent.name, intent.colorArgb)
            is ProgramTypesIntent.Rename -> rename(intent.name)
            is ProgramTypesIntent.Recolor -> recolor(intent.colorArgb)
            ProgramTypesIntent.Remove -> remove()
        }
    }

    /**
     * The catalog. [selectId] arms the brush with a freshly created programme
     * - that is what the operator created it FOR. A brush whose programme
     * vanished from the catalog is disarmed through the flow, so the grid's
     * «Προβολή Βάσει… Πρόγραμμα» cannot stay scoped to a dead id.
     */
    private fun load(selectId: Long? = null) {
        viewModelScope.launch {
            when (val result = repository.list()) {
                is DataResult.Success -> {
                    val programs = result.data.toImmutableList()
                    _state.update { it.copy(programs = programs) }
                    // Re-push the armed programme from the FRESH catalog: a
                    // rename or recolour changes the copy the grid header draws,
                    // and a delete drops it entirely.
                    val keep = selectId ?: _state.value.armedId
                    common.selectProgram(programs.firstOrNull { it.id == keep })
                }
                is DataResult.Failure -> showSnackbar(result.error.toUiText())
            }
        }
    }

    private fun create(name: String, colorArgb: Int?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            when (val result = repository.create(trimmed, colorArgb)) {
                is DataResult.Success -> {
                    _state.update { it.copy(dialog = null) }
                    load(selectId = result.data.id)
                }
                is DataResult.Failure -> showSnackbar(result.error.toUiText())
            }
        }
    }

    private fun rename(name: String) {
        val program = _state.value.selected ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == program.name) {
            _state.update { it.copy(dialog = null) }
            return
        }
        viewModelScope.launch {
            when (val result = repository.update(program.id, name = trimmed)) {
                is DataResult.Success -> {
                    _state.update { it.copy(dialog = null) }
                    load()
                    // Cells show the programme NAME - re-fetch so they follow.
                    common.refreshMonth()
                }
                is DataResult.Failure -> showSnackbar(result.error.toUiText())
            }
        }
    }

    private fun recolor(colorArgb: Int) {
        val program = _state.value.selected ?: return
        viewModelScope.launch {
            when (val result = repository.update(program.id, colorArgb = colorArgb)) {
                is DataResult.Success -> {
                    _state.update { it.copy(dialog = null) }
                    load()
                    // Recoloring repaints every cell whose break carries the
                    // programme - the colour is data ON the programme.
                    common.refreshMonth()
                }
                is DataResult.Failure -> showSnackbar(result.error.toUiText())
            }
        }
    }

    private fun remove() {
        val program = _state.value.selected ?: return
        viewModelScope.launch {
            when (val result = repository.remove(program.id)) {
                is DataResult.Success -> {
                    // Soft delete: painted cells keep their colours (the server
                    // keeps the row) - only the catalog loses the entry. The
                    // brush must be disarmed THROUGH the flow, or the grid stays
                    // filtered on a programme that is no longer selectable.
                    _state.update { it.copy(dialog = null) }
                    common.selectProgram(null)
                    load()
                }
                is DataResult.Failure -> showSnackbar(result.error.toUiText())
            }
        }
    }
}
