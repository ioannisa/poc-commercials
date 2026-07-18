package eu.anifantakis.commercials.feature.ai_chat.presentation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.anifantakis.commercials.core.domain.auth.AiChatProviderOption
import eu.anifantakis.commercials.core.presentation.navigation.Navigator
import eu.anifantakis.commercials.feature.ai_chat.presentation.screens.ai_chat.AiChatScreenRoot
import kotlinx.serialization.Serializable

@Serializable
sealed interface AiChatNavType : NavKey {
    @Serializable
    data object AiChat : AiChatNavType
}

/**
 * [providers] reads the SESSION's AI provider catalog (a lambda, so the entry
 * always sees the current value - the keep-alive can change it mid-session).
 */
fun EntryProviderScope<NavKey>.aiChatEntries(
    navigator: Navigator,
    providers: () -> List<AiChatProviderOption>,
) {
    entry<AiChatNavType.AiChat> {
        AiChatScreenRoot(providers = providers, onBack = { navigator.goBack() })
    }
}
