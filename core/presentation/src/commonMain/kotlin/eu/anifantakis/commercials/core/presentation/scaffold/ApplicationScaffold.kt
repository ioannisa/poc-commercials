package eu.anifantakis.commercials.core.presentation.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.anifantakis.commercials.core.presentation.design_system.components.AppLoadingIndicator
import eu.anifantakis.commercials.core.presentation.global_state.GlobalEffect
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.core.presentation.helper.ObserveEffects
import org.koin.compose.koinInject

/**
 * Wraps the NavDisplay and owns app-wide chrome: the ONLY snackbar host in
 * the app (ViewModels reach it via BaseGlobalViewModel.showSnackbar through
 * the global state container) and the global loading overlay
 * ([AppLoadingIndicator]). Snackbar text arrives as a UiText and resolves
 * HERE, at display time, in the active language (golden-standard rule).
 * Screens keep their own console-style headers by design (legacy look), so
 * there is no shared top bar.
 */
@Composable
fun ApplicationScaffold(
    globalStateContainer: GlobalStateContainer = koinInject(),
    content: @Composable (PaddingValues) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // GLOBAL effects render here, once, for the whole app
    ObserveEffects(globalStateContainer.effects) { effect ->
        when (effect) {
            is GlobalEffect.SnackBarMessage ->
                snackbarHostState.showSnackbar(
                    message = effect.message.resolve(),
                    actionLabel = effect.actionLabel,
                )
        }
    }

    val globalState by globalStateContainer.state.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            content(padding)
            AppLoadingIndicator(
                isLoading = globalState.isLoading,
                isCritical = globalState.isCriticalLoading,
            )
        }
    }
}
