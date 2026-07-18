package eu.anifantakis.commercials.feature.ai_chat.domain

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * One persisted turn of a saved conversation - the TRANSCRIPT only (role,
 * text, tool names). Confirmation cards are deliberately NOT persisted:
 * they are live-action UI, and their outcome already survives as the NOTE
 * turns the ViewModel appends when a card is approved/declined/failed.
 */
@Immutable
@Serializable
data class AiChatStoredTurn(
    val role: String,
    val text: String,
    val tools: List<String> = emptyList(),
)

/** One saved conversation; [title] is the first user message, truncated. */
@Immutable
@Serializable
data class AiChatConversation(
    val id: String,
    val title: String,
    val updatedAtEpochMs: Long,
    val turns: List<AiChatStoredTurn> = emptyList(),
)

/**
 * Persistent conversation history (KSafe-backed, capped at
 * [MAX_CONVERSATIONS] - oldest fall off). The ViewModel AUTOSAVES the live
 * conversation on every turn, so there is no save button to forget.
 */
interface AiChatHistoryStore {
    /** Newest first. */
    fun load(): List<AiChatConversation>
    fun upsert(conversation: AiChatConversation)
    fun delete(id: String)

    companion object {
        const val MAX_CONVERSATIONS = 30
    }
}
