package eu.anifantakis.commercials.feature.ai_chat.domain

import androidx.compose.runtime.Immutable

/**
 * Who authored a chat turn. [NOTE] is a system-side annotation the client
 * itself appends (an action was approved/declined/failed) - rendered as a
 * small centred line, and sent to the model as context on the next turn.
 */
enum class AiChatRole { USER, ASSISTANT, NOTE }

/** One visible turn of the conversation (tool traffic never reaches the client). */
@Immutable
data class AiChatMessage(
    val role: AiChatRole,
    val text: String,
    /** For assistant turns: the tools the server ran to produce this answer. */
    val steps: List<AiToolStep> = emptyList(),
    /** For assistant turns: mutations the model PREPARED, awaiting the user's approval. */
    val proposals: List<AiProposal> = emptyList(),
)

/** One tool the assistant called while answering. */
@Immutable
data class AiToolStep(val tool: String, val isError: Boolean)

/**
 * A mutation the model prepared server-side (validated dry-run, nothing
 * performed): the client renders it as a CONFIRMATION CARD; approving sends
 * [tool] + [argumentsJson] back for execution. [id] is client-generated -
 * it keys the card's execution state in the screen state.
 */
@Immutable
data class AiProposal(
    val id: String,
    val tool: String,
    val argumentsJson: String,
    val preview: String,
)

/**
 * A UI action the assistant asked the app to perform on receipt. Only known
 * actions execute; anything else is ignored. `switch_station` carries the
 * target [station] id - the app changes its active station (grant-checked).
 */
@Immutable
data class AiClientAction(
    val action: String,
    val station: String? = null,
    /** open_report: the parked report's one-shot id + display file name. */
    val reportId: String? = null,
    val fileName: String? = null,
)

/** The assistant's reply to one request. */
@Immutable
data class AiChatReply(
    val text: String,
    val steps: List<AiToolStep>,
    val proposals: List<AiProposal> = emptyList(),
    val clientActions: List<AiClientAction> = emptyList(),
)

/** The outcome of executing an approved proposal (isError = the tool refused). */
@Immutable
data class AiExecutionOutcome(val text: String, val isError: Boolean)
