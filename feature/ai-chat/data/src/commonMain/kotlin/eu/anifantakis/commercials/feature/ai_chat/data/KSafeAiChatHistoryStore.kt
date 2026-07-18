package eu.anifantakis.commercials.feature.ai_chat.data

import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatConversation
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatHistoryStore
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.invoke

/**
 * [AiChatHistoryStore] over KSafe (encrypted, same store as the session).
 * One key holds the whole list; the ViewModel keeps the live value in its
 * own state, so no reactive mirror is needed here. Koin singleton.
 */
class KSafeAiChatHistoryStore(private val ksafe: KSafe) : AiChatHistoryStore {

    private var stored: List<AiChatConversation> by ksafe(emptyList(), key = "ai_chat_history")

    override fun load(): List<AiChatConversation> = stored.sortedByDescending { it.updatedAtEpochMs }

    override fun upsert(conversation: AiChatConversation) {
        stored = (listOf(conversation) + stored.filter { it.id != conversation.id })
            .sortedByDescending { it.updatedAtEpochMs }
            .take(AiChatHistoryStore.MAX_CONVERSATIONS)
    }

    override fun delete(id: String) {
        stored = stored.filter { it.id != id }
    }
}
