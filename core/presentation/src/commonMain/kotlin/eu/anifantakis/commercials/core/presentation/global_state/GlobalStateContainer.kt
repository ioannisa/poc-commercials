package eu.anifantakis.commercials.core.presentation.global_state

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The single app-wide MVI container: dispatch [GlobalIntent]s, read
 * [state], render [effects] once at the application scaffold. Koin
 * singleton.
 */
class GlobalStateContainer(initialState: GlobalState = GlobalState()) {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<GlobalState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<GlobalEffect>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<GlobalEffect> = _effects

    fun dispatch(action: GlobalIntent) {
        val newState = reduce(_state.value, action)
        if (_state.value != newState) _state.update { newState }
    }

    /** Pure copy-based reducer; snackbars emit an effect, state unchanged. */
    private fun reduce(state: GlobalState, action: GlobalIntent): GlobalState = when (action) {
        GlobalIntent.ShowLoading -> state.copy(isLoading = true)
        GlobalIntent.HideLoading -> state.copy(isLoading = false)
        is GlobalIntent.UpdateHasContent -> state.copy(hasContent = action.hasContent)
        is GlobalIntent.ShowSnackbar -> {
            _effects.tryEmit(GlobalEffect.SnackBarMessage(action.message, action.actionLabel))
            state
        }
    }
}
