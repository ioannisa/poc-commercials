package eu.anifantakis.commercials.feature.ai_chat.domain

import androidx.compose.runtime.Immutable

/** Who authored a chat turn. */
enum class AiChatRole { USER, ASSISTANT }

/** One visible turn of the conversation (tool traffic never reaches the client). */
@Immutable
data class AiChatMessage(
    val role: AiChatRole,
    val text: String,
    /** For assistant turns: the tools the server ran to produce this answer. */
    val steps: List<AiToolStep> = emptyList(),
)

/** One tool the assistant called while answering. */
@Immutable
data class AiToolStep(val tool: String, val isError: Boolean)

/** The assistant's reply to one request. */
@Immutable
data class AiChatReply(val text: String, val steps: List<AiToolStep>)
