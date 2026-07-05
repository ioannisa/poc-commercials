package eu.anifantakis.commercials.core.presentation.global_state

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The serialized-reducer kernel for every `<Feature>CommonViewModel`
 * (kmp-developer shared-flow pattern): contract verbs enqueue
 * `<Feature>CommonIntent`s, and ONE reducer coroutine executes them strictly
 * in arrival order - commands arriving concurrently from several screens can
 * never interleave a read-modify-write on the shared state. That
 * serialization is this class's whole reason to exist.
 *
 * - [commonState] is a raw StateFlow: its consumers are the flow's screen
 *   ViewModels (they merge slices into their own states), never the UI.
 * - [commonEffects] has exactly ONE legitimate collector - the flow host
 *   that drives step navigation. Pure state-sharing flows use `Nothing`
 *   for [E] and simply never emit.
 *
 * Extends [BaseGlobalViewModel], so every CommonViewModel keeps
 * withLoading/showSnackbar and the rest of the app-wide machinery.
 */
abstract class BaseCommonViewModel<S : Any, I : Any, E : Any>(
    initialState: S,
) : BaseGlobalViewModel() {

    private val intents = Channel<I>(Channel.BUFFERED)

    private val _commonState = MutableStateFlow(initialState)
    val commonState: StateFlow<S> = _commonState.asStateFlow()

    private val effectChannel = Channel<E>(Channel.BUFFERED)
    val commonEffects: Flow<E> = effectChannel.receiveAsFlow()

    protected val currentState: S get() = _commonState.value

    init {
        // ONE reducer coroutine - the serialization guarantee.
        viewModelScope.launch { for (intent in intents) reduce(intent) }
    }

    protected fun dispatch(intent: I) {
        // A command arriving AFTER the flow died (pop teardown races an
        // in-flight coroutine or a tap during the pop animation) is by
        // definition irrelevant - drop it, never crash on it.
        intents.trySend(intent)
    }

    protected fun updateCommonState(transform: (S) -> S) = _commonState.update(transform)

    protected suspend fun emitCommonEffect(effect: E) = effectChannel.send(effect)

    protected abstract suspend fun reduce(intent: I)

    override fun onCleared() {
        intents.close()
        effectChannel.close()
        super.onCleared()
    }
}
