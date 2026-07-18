package eu.anifantakis.commercials.server.ai

import kotlinx.serialization.json.JsonObject

/**
 * The in-app AI assistant: ports + shared shapes (Way-1 DIP - one package,
 * one port, one adapter per LLM provider, one organizer [AiChatService]).
 *
 * The contract is deliberately COARSE: a provider owns its entire agentic
 * loop (model <-> tools until a final answer), because every vendor has a
 * different wire format for tool calls and their in-loop message history.
 * What crosses the port is only what the app cares about: the conversation,
 * the bridged tools, and the final reply with its tool-step trail.
 */
interface AiChatProvider {
    suspend fun chat(
        system: String,
        history: List<AiChatTurn>,
        tools: List<AiBridgedTool>,
        model: String,
        maxTokens: Int,
    ): AiChatReply
}

/** One client-visible turn of the conversation (tool traffic never leaves the server). */
data class AiChatTurn(val role: AiChatRole, val text: String)

enum class AiChatRole { USER, ASSISTANT }

/**
 * One tool the model may call, bridged from the MCP registry. The schema
 * pieces are plain JSON-schema fragments ([properties] + [required]) that
 * each adapter re-wraps into its provider's dialect. Read tools execute
 * directly; MUTATING tools are bridged as forced DRY-RUNS whose previews
 * become [AiProposal] confirmation cards - the user's approval executes.
 */
class AiBridgedTool(
    val name: String,
    val description: String,
    val properties: JsonObject,
    val required: List<String>,
    val execute: suspend (JsonObject) -> AiToolOutcome,
)

data class AiToolOutcome(val text: String, val isError: Boolean)

/**
 * A mutation the model PREPARED but did not perform: the bridge forced the
 * tool's dry-run mode, [preview] is its validated output, and [arguments]
 * (with any `confirm` stripped) are what the approval endpoint replays with
 * `confirm=true` once the user presses the card's approve button.
 */
data class AiProposal(val tool: String, val arguments: JsonObject, val preview: String)

/**
 * A UI action the model asked the CLIENT to perform - nothing touches data
 * server-side, the app executes it on receipt. First case: `switch_station`
 * with a `station` argument: the app changes its active station and the
 * conversation's station pin follows on the next request.
 */
data class AiClientAction(val action: String, val arguments: JsonObject)

/** The final answer plus the (name-only) trail of tool calls it took. */
data class AiChatReply(
    val text: String,
    val steps: List<AiToolStep>,
    val proposals: List<AiProposal> = emptyList(),
    val clientActions: List<AiClientAction> = emptyList(),
)

data class AiToolStep(val tool: String, val isError: Boolean)

/** A provider-side failure the route surfaces as a clean 502 with this message. */
class AiProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Hard cap on model<->tool rounds per request - the runaway guard. */
internal const val MAX_TOOL_ROUNDS = 8
