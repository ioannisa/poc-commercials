package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.server.ai.AiChatRole
import eu.anifantakis.commercials.server.ai.AiChatService
import eu.anifantakis.commercials.server.ai.AiChatTurn
import eu.anifantakis.commercials.server.ai.AiProviderException
import eu.anifantakis.commercials.server.ai.AiSelectionException
import eu.anifantakis.commercials.server.plugins.AI_CHAT_RATE_LIMIT
import eu.anifantakis.commercials.server.plugins.authUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One client-visible conversation turn; role is `user` or `assistant`. */
@Serializable
data class AiChatTurnDto(val role: String, val text: String)

/**
 * [provider]/[model] are the user's dropdown picks; null = the server default
 * (first configured provider, its first model). Both are validated against
 * the server.yaml catalog - anything outside it is a 400.
 */
@Serializable
data class AiChatRequestDto(
    val messages: List<AiChatTurnDto>,
    val provider: String? = null,
    val model: String? = null,
)

@Serializable
data class AiToolStepDto(val tool: String, val isError: Boolean = false)

/**
 * A mutation the model PREPARED (validated dry-run) - the client renders it
 * as a confirmation card; approving POSTs [tool]+[arguments] to /execute.
 */
@Serializable
data class AiProposalDto(
    val tool: String,
    val arguments: JsonObject,
    val preview: String,
)

/** A UI action for the CLIENT to perform on receipt (e.g. switch_station). */
@Serializable
data class AiClientActionDto(val action: String, val arguments: JsonObject)

@Serializable
data class AiChatResponseDto(
    val text: String,
    val steps: List<AiToolStepDto> = emptyList(),
    val proposals: List<AiProposalDto> = emptyList(),
    val clientActions: List<AiClientActionDto> = emptyList(),
)

@Serializable
data class AiExecuteRequestDto(val tool: String, val arguments: JsonObject)

@Serializable
data class AiExecuteResponseDto(val text: String, val isError: Boolean = false)

/** Keep a runaway client (or a very long chat) from flooding the provider. */
private const val MAX_TURNS = 40
private const val MAX_TURN_CHARS = 8_000

/**
 * The in-app AI assistant. Mounted (inside the bearer-auth block) only when
 * server.yaml configures `ai:` - the server proxies every chat to the
 * configured LLM provider, running the MCP read tools as the CALLING user, so
 * the model sees exactly what the user's grants allow. The provider API key
 * never leaves the server.
 */
fun Route.aiRoutes(aiChat: AiChatService) {
    rateLimit(AI_CHAT_RATE_LIMIT) {
        route("/api/ai") {
            /**
             * One chat request: the full visible conversation in, the assistant's
             * reply (plus the tool-call trail) out. Stateless - the client resends
             * the history it wants the model to see.
             *
             * Tag: AI
             */
            post("/chat") {
                val user = call.authUser()
                val request = call.receive<AiChatRequestDto>()
                if (request.messages.isEmpty() || request.messages.size > MAX_TURNS ||
                    request.messages.any { it.text.length > MAX_TURN_CHARS }
                ) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "1..$MAX_TURNS messages of up to $MAX_TURN_CHARS chars"))
                    return@post
                }
                val history = request.messages.map {
                    AiChatTurn(
                        role = if (it.role.equals("assistant", ignoreCase = true)) AiChatRole.ASSISTANT else AiChatRole.USER,
                        text = it.text,
                    )
                }
                // The app's ApiHttpClient stamps ?station=<selected id> on every
                // request - the chat inherits the app's ACTIVE station and the
                // model is told to scope everything to it (no "which station?").
                val station = call.request.queryParameters["station"]?.trim()?.takeIf { it.isNotEmpty() }
                try {
                    val reply = aiChat.chat(user, history, request.provider, request.model, station)
                    call.respond(
                        AiChatResponseDto(
                            text = reply.text,
                            steps = reply.steps.map { AiToolStepDto(it.tool, it.isError) },
                            proposals = reply.proposals.map { AiProposalDto(it.tool, it.arguments, it.preview) },
                            clientActions = reply.clientActions.map { AiClientActionDto(it.action, it.arguments) },
                        )
                    )
                } catch (e: AiSelectionException) {
                    // A provider/model outside the server.yaml catalog is a CLIENT error.
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid provider/model")))
                } catch (e: AiProviderException) {
                    // Provider/config trouble is an upstream problem, not a client one.
                    call.respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "AI provider error")))
                }
            }

            /**
             * Execute a confirmation card the user APPROVED: replays the
             * prepared tool call with confirm=true, as the calling user.
             * Everything is re-validated server-side (mutation gate, staff
             * role, station pin, and the tool's own data checks) - a stale
             * proposal fails honestly with the tool's error. A tool-level
             * failure is a 200 with isError=true; only an invalid REQUEST
             * (unknown tool, wrong station, mutations off) is a 400.
             *
             * Tag: AI
             */
            post("/execute") {
                val user = call.authUser()
                val request = call.receive<AiExecuteRequestDto>()
                val station = call.request.queryParameters["station"]?.trim()?.takeIf { it.isNotEmpty() }
                try {
                    val outcome = aiChat.executeProposal(user, station, request.tool, request.arguments)
                    call.respond(AiExecuteResponseDto(outcome.text, outcome.isError))
                } catch (e: AiSelectionException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid action")))
                }
            }
        }
    }
}
