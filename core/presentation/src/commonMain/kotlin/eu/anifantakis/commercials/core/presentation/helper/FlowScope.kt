package eu.anifantakis.commercials.core.presentation.helper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * A ViewModelStoreOwner that outlives individual nav entries, cleared on
 * dispose (kmp-developer navigation3 flow-scope helper). Hosts a flow's
 * shared ViewModel - e.g. a <Feature>CommonViewModel resolved by several
 * entries with koinViewModel(viewModelStoreOwner = flowOwner) - so all
 * entries of the flow get the SAME instance, destroyed when the owner
 * leaves composition.
 */
@Composable
fun rememberFlowViewModelStoreOwner(key: String): ViewModelStoreOwner {
    val owner = remember(key) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(key) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}
