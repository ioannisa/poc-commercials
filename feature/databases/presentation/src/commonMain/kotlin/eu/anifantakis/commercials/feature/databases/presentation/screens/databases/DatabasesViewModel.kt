package eu.anifantakis.commercials.feature.databases.presentation.screens.databases

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.util.toDisplayMessage
import eu.anifantakis.commercials.feature.databases.domain.DatabasesRepository
import eu.anifantakis.commercials.feature.databases.domain.HostedStation
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The delete confirmation dialog's sub-state (same screen, same VM). */
@Immutable
data class DeleteDialogState(
    val station: HostedStation,
    val hard: Boolean = false,
    val confirmId: String = "",
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canConfirm: Boolean get() = !busy && confirmId.trim() == station.id
}

@Immutable
data class DatabasesState(
    val stations: ImmutableList<HostedStation> = persistentListOf(),
    val message: String? = null,
    val error: String? = null,
    val delete: DeleteDialogState? = null,
)

sealed interface DatabasesIntent {
    data object Reload : DatabasesIntent
    data class DeleteRequested(val station: HostedStation) : DatabasesIntent
    data class DeleteModeChanged(val hard: Boolean) : DatabasesIntent
    data class ConfirmIdChanged(val value: String) : DatabasesIntent
    data object ConfirmDelete : DatabasesIntent
    data object DismissDelete : DatabasesIntent
}

@Stable
class DatabasesViewModel(
    private val repository: DatabasesRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(DatabasesState())
    val state by _state
        .onStart { reload() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), _state.value)
        .toComposeState(viewModelScope)

    fun onAction(intent: DatabasesIntent) {
        when (intent) {
            DatabasesIntent.Reload -> reload()
            is DatabasesIntent.DeleteRequested ->
                _state.update { it.copy(delete = DeleteDialogState(intent.station)) }
            is DatabasesIntent.DeleteModeChanged ->
                _state.update { it.copy(delete = it.delete?.copy(hard = intent.hard)) }
            is DatabasesIntent.ConfirmIdChanged ->
                _state.update { it.copy(delete = it.delete?.copy(confirmId = intent.value)) }
            DatabasesIntent.DismissDelete ->
                _state.update { if (it.delete?.busy == true) it else it.copy(delete = null) }
            DatabasesIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private fun reload() {
        viewModelScope.launch {
            when (val result = repository.listStations()) {
                is DataResult.Success -> _state.update {
                    it.copy(stations = result.data.toImmutableList(), error = null)
                }
                is DataResult.Failure -> _state.update {
                    it.copy(error = result.error.toDisplayMessage())
                }
            }
        }
    }

    private fun confirmDelete() {
        val dialog = _state.value.delete ?: return
        if (!dialog.canConfirm) return
        _state.update { it.copy(delete = dialog.copy(busy = true, error = null)) }
        viewModelScope.launch {
            when (val result = repository.deleteStation(dialog.station.id, dialog.hard, dialog.confirmId.trim())) {
                is DataResult.Success -> {
                    _state.update {
                        it.copy(
                            delete = null,
                            message = "${result.data.status} (grants removed: ${result.data.grantsRemoved})",
                        )
                    }
                    reload()
                }
                is DataResult.Failure -> _state.update {
                    it.copy(delete = it.delete?.copy(busy = false, error = result.error.toDisplayMessage()))
                }
            }
        }
    }
}
