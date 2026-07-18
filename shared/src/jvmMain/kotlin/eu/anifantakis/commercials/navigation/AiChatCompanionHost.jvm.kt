package eu.anifantakis.commercials.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
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
 * Desktop: the companion is a REAL, resizable, always-on-top OS window - the
 * main window keeps its full width (a small display never squeezes the
 * schedule), and the chat can be parked anywhere, second monitor included.
 *
 * [DialogWindow] (not `Window`) deliberately: it is composable from INSIDE
 * the app's composition, so the theme/locale/Koin CompositionLocals - and
 * crucially the ViewModelStoreOwner - flow straight in: the conversation is
 * the SAME one the docked variant would show, and it survives closing the
 * window. Width is seeded from (and persisted to) the same silent preference
 * the docked panel uses. [windowWidth] is unused here - the OS window does
 * not take space from the main content.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun AiChatCompanionHost(
    visible: Boolean,
    windowWidth: Dp,
    providers: () -> List<AiChatProviderOption>,
    onClose: () -> Unit,
) {
    if (!visible) return
    val prefs = koinInject<AiChatPreferences>()
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
            )
        }
    }
}
