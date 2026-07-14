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
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import eu.anifantakis.commercials.core.presentation.design_system.CommercialsTheme
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.global_state.GlobalState
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationProvider
import androidx.compose.ui.tooling.preview.Preview
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

/*
 * The scaffold's Koin dependency is a DEFAULT argument, not a body call - which is
 * exactly what lets a preview hand it a plain container and never start Koin.
 *
 * The three previews below are the three things the scaffold is FOR: the idle
 * chrome, the ambient (non-blocking) loading bar, and the critical overlay that
 * blocks the whole app. The last one in particular can only be judged by looking
 * at it on top of real content.
 */

@Preview
@Composable
private fun ApplicationScaffoldPreview() = CommercialsTheme {
    LocalizationProvider {
        ApplicationScaffold(globalStateContainer = GlobalStateContainer()) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                AppText("Timetable", AppTextStyle.SCREEN_TITLE)
            }
        }
    }
}

@Preview
@Composable
private fun ApplicationScaffoldLoadingPreview() = CommercialsTheme {
    LocalizationProvider {
        ApplicationScaffold(
            globalStateContainer = GlobalStateContainer(GlobalState(isLoading = true)),
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                AppText("Content stays usable while this loads", AppTextStyle.BODY)
            }
        }
    }
}

@Preview
@Composable
private fun ApplicationScaffoldCriticalLoadingPreview() = CommercialsTheme {
    LocalizationProvider {
        ApplicationScaffold(
            globalStateContainer = GlobalStateContainer(GlobalState(isCriticalLoading = true)),
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                AppText("This content is blocked", AppTextStyle.BODY)
            }
        }
    }
}
