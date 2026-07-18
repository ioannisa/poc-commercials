package eu.anifantakis.commercials.feature.ai_chat.data

import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatPreferences
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.invoke

/**
 * [AiChatPreferences] over KSafe (the same store as the session). Persistent
 * storage only - the chat ViewModel and the panel host keep the live values
 * in their own state, so no Compose-observable mirror is needed here.
 * Koin singleton.
 */
class KSafeAiChatPreferences(private val ksafe: KSafe) : AiChatPreferences {
    override var provider: String by ksafe("", key = "ai_chat_provider")
    override var model: String by ksafe("", key = "ai_chat_model")
    override var panelWidthDp: Int by ksafe(AiChatPreferences.DEFAULT_PANEL_WIDTH_DP, key = "ai_chat_panel_width")
}
