package eu.anifantakis.commercials.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogModalityType
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import eu.anifantakis.commercials.core.domain.auth.AiChatProviderOption
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatPreferences
import eu.anifantakis.commercials.feature.ai_chat.presentation.screens.ai_chat.AiChatScreenRoot
import org.koin.compose.koinInject

/**
 * Desktop: BOTH companion forms, the user's pick persisted. Default is the
 * in-app OVERLAY (same as the web) with a "detach" header action; detaching
 * moves the chat into a separate, resizable, always-on-top, MODELESS OS
 * window (the main window stays fully interactive), whose "attach" action
 * docks it back. The conversation survives every hop - the ViewModel lives
 * at root scope, both hosts render the same one.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal actual fun AiChatCompanionHost(
    visible: Boolean,
    windowWidth: Dp,
    providers: () -> List<AiChatProviderOption>,
    onClose: () -> Unit,
) {
    val prefs = koinInject<AiChatPreferences>()
    var detached by remember { mutableStateOf(prefs.detached) }

    if (!detached) {
        OverlayAiChatPanel(
            visible = visible,
            windowWidth = windowWidth,
            providers = providers,
            onClose = onClose,
            onDetach = {
                detached = true
                prefs.detached = true
            },
        )
        return
    }
    if (!visible) return
    val state = rememberDialogState(size = DpSize(prefs.panelWidthDp.dp, 700.dp))
    DialogWindow(
        onCloseRequest = {
            state.size.width.value.toInt().takeIf { it > 0 }?.let { prefs.panelWidthDp = it }
            onClose()
        },
        state = state,
        title = Strings[StringKey.AI_CHAT_TITLE],
        resizable = true,
        alwaysOnTop = true,
        // The high-level overload hardcodes DocumentModal, which would BLOCK
        // the main window - this is a companion, the app must stay clickable.
        modalityType = DialogModalityType.Modeless,
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AiChatScreenRoot(
                providers = providers,
                onClose = onClose,
                modifier = Modifier.fillMaxSize(),
                onAttach = {
                    state.size.width.value.toInt().takeIf { it > 0 }?.let { prefs.panelWidthDp = it }
                    detached = false
                    prefs.detached = false
                },
            )
        }
    }
}
