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
 * singleton. Dispatch from the main thread (this IS UI state).
 *
 * Loading is REF-COUNTED (AndroidSkeletonApp ObservableLoadingCounter idea):
 * overlapping `withLoading` blocks keep the overlay up until the LAST one
 * finishes — a plain boolean would drop the overlay when the FIRST block
 * ends. Criticality is a separate count so an overlapping non-critical load
 * cannot lift a critical one's back-blocking.
 */
class GlobalStateContainer(initialState: GlobalState = GlobalState()) {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<GlobalState> = _state.asStateFlow()

    private var loadingCount = 0
    private var criticalCount = 0

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

    /** Copy-based reducer; snackbars emit an effect, state unchanged. */
    private fun reduce(state: GlobalState, action: GlobalIntent): GlobalState = when (action) {
        is GlobalIntent.ShowLoading -> {
            loadingCount++
            if (action.critical) criticalCount++
            state.copy(isLoading = true, isCriticalLoading = criticalCount > 0)
        }
        is GlobalIntent.HideLoading -> {
            loadingCount = (loadingCount - 1).coerceAtLeast(0)
            if (action.critical) criticalCount = (criticalCount - 1).coerceAtLeast(0)
            state.copy(isLoading = loadingCount > 0, isCriticalLoading = criticalCount > 0)
        }
        is GlobalIntent.UpdateHasContent -> state.copy(hasContent = action.hasContent)
        is GlobalIntent.ShowSnackbar -> {
            _effects.tryEmit(GlobalEffect.SnackBarMessage(action.message, action.actionLabel))
            state
        }
    }
}
