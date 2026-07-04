package eu.anifantakis.commercials.core.presentation.helper

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Bridges a StateFlow to Compose [State] once, in the ViewModel - the UI
 * then reads `viewModel.state` directly, no collectAsStateWithLifecycle()
 * at every call site (kmp-developer MVI convention).
 */
fun <T> StateFlow<T>.toComposeState(scope: CoroutineScope): State<T> {
    val composeState = mutableStateOf(value)
    scope.launch { collect { composeState.value = it } }
    return composeState
}
