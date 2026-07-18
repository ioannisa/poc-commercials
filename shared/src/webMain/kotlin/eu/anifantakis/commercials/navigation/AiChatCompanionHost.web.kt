package eu.anifantakis.commercials.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import eu.anifantakis.commercials.core.domain.auth.AiChatProviderOption

/** Browsers have no OS windows to give us - the docked side panel it is. */
@Composable
internal actual fun AiChatCompanionHost(
    visible: Boolean,
    windowWidth: Dp,
    providers: () -> List<AiChatProviderOption>,
    onClose: () -> Unit,
) = DockedAiChatPanel(visible, windowWidth, providers, onClose)
