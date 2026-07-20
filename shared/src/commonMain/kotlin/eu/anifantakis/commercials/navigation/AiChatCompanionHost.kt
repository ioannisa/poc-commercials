package eu.anifantakis.commercials.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.domain.auth.AiChatProviderOption
import eu.anifantakis.commercials.core.presentation.design_system.components.AppSlideOverPanel
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatPreferences
import eu.anifantakis.commercials.feature.ai_chat.presentation.screens.ai_chat.AiChatScreenRoot
import eu.anifantakis.commercials.feature.ai_chat.presentation.screens.ai_chat.AiChatViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * HOW the AI companion is hosted is a platform decision:
 *
 * - **Web (and the compile-only mobile targets)**: an [AppSlideOverPanel] -
 *   it COVERS the content instead of squeezing it, so the app never reflows;
 *   the uncovered part stays fully interactive, and a collapse chevron parks
 *   the panel into a slim re-expand tab without losing anything.
 * - **Desktop (jvm)**: the same overlay by default, PLUS a "detach" header
 *   action that moves the chat into a separate, resizable, always-on-top OS
 *   window (and "attach" brings it back). The choice persists.
 *
 * Rendered as a TOP layer of [NavigationRoot]'s content box; [windowWidth]
 * caps the drag-resize so some app is always visible beside the panel.
 */
@Composable
internal expect fun AiChatCompanionHost(
    visible: Boolean,
    windowWidth: Dp,
    providers: () -> List<AiChatProviderOption>,
    onClose: () -> Unit,
)

/**
 * The in-app OVERLAY companion. The panel chrome - slide-in, drag-resize,
 * collapse-to-tab, RTL mirroring - is [AppSlideOverPanel]; what is left here
 * is which screen fills it and where its width persists.
 */
@Composable
internal fun OverlayAiChatPanel(
    visible: Boolean,
    windowWidth: Dp,
    providers: () -> List<AiChatProviderOption>,
    onClose: () -> Unit,
    onDetach: (() -> Unit)? = null,
    viewModel: AiChatViewModel = koinViewModel(),
) {
    val prefs = koinInject<AiChatPreferences>()
    AppSlideOverPanel(
        visible = visible,
        windowWidth = windowWidth,
        initialWidth = prefs.panelWidthDp.dp,
        onWidthCommitted = { prefs.panelWidthDp = it.value.toInt() },
    ) { modifier, onCollapse ->
        AiChatScreenRoot(
            providers = providers,
            onClose = onClose,
            modifier = modifier,
            onCollapse = onCollapse,
            onDetach = onDetach,
            viewModel = viewModel,
        )
    }
}
