package eu.anifantakis.commercials.server.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI over the Responses API (`/v1/responses`) with raw HTTP. Wire shapes
 * are hand-built with kotlinx-json - the slice we need is small and stable,
 * and it keeps the server on one HTTP/JSON stack.
 *
 * Responses, NOT Chat Completions, deliberately: GPT-5.6 models reject
 * function tools alongside (default-on) reasoning at `/v1/chat/completions` -
 * "use /v1/responses or set reasoning_effort to 'none'", and disabling
 * reasoning would lobotomize the agentic loop. The loop here is stateful on
 * OpenAI's side: each round sends only the `function_call_output` items plus
 * `previous_response_id`, so reasoning state carries between rounds without
 * us echoing anything back.
 */
class OpenAiAiProvider(
    private val apiKey: String,
    private val http: HttpClient,
) : AiChatProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun chat(
        system: String,
        history: List<AiChatTurn>,
        tools: List<AiBridgedTool>,
        model: String,
        maxTokens: Int,
    ): AiChatReply {
        val byName = tools.associateBy { it.name }
        // Responses-API tool defs are FLAT (name at the top level), unlike
        // Chat Completions' {type, function:{...}} nesting.
        val toolDefs = buildJsonArray {
            tools.forEach { t ->
                add(buildJsonObject {
                    put("type", "function")
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", t.properties)
                        put("required", JsonArray(t.required.map { JsonPrimitive(it) }))
                    })
                })
            }
        }
        val firstInput = buildJsonArray {
            history.forEach { turn ->
                add(buildJsonObject {
                    put("role", if (turn.role == AiChatRole.USER) "user" else "assistant")
                    put("content", turn.text)
                })
            }
        }
        val steps = mutableListOf<AiToolStep>()
        var previousResponseId: String? = null
        var input: JsonArray = firstInput
        var inTok = 0L
        var outTok = 0L

        repeat(MAX_TOOL_ROUNDS) {
            val body = buildJsonObject {
                put("model", model)
                put("max_output_tokens", maxTokens)
                // Instructions ride previous_response_id state after round 1,
                // but resending is documented-safe and keeps rounds uniform.
                put("instructions", system)
                put("input", input)
                if (tools.isNotEmpty()) put("tools", toolDefs)
                previousResponseId?.let { put("previous_response_id", it) }
            }
            // Transport failures (timeouts, DNS, resets) become a clean 502
            // with the provider named - never an unhandled 500/406.
            val response = try {
                http.post("https://api.openai.com/v1/responses") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw AiProviderException("OpenAI: ${e.message ?: "network failure"}", e)
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw AiProviderException("OpenAI ${response.status.value}: ${errorMessage(text)}")
            }
            val parsed = json.parseToJsonElement(text).jsonObject
            (parsed["error"] as? JsonObject)?.let {
                throw AiProviderException("OpenAI: ${it["message"]?.jsonPrimitive?.contentOrNull ?: it}")
            }
            previousResponseId = parsed["id"]?.jsonPrimitive?.contentOrNull
                ?: throw AiProviderException("OpenAI: response without id")
            (parsed["usage"] as? JsonObject)?.let { u ->
                inTok += (u["input_tokens"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
                outTok += (u["output_tokens"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
            }
            val output = parsed["output"]?.jsonArray.orEmpty()

            val calls = output.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "function_call" }
            if (calls.isEmpty()) {
                return AiChatReply(outputText(output), steps, usage = AiUsage(inTok, outTok))
            }

            // Execute this round's calls; the next round sends ONLY their
            // outputs - previous_response_id carries everything else.
            input = buildJsonArray {
                calls.forEach { call ->
                    val obj = call.jsonObject
                    val callId = obj["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val args = obj["arguments"]?.jsonPrimitive?.contentOrNull
                        ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                        ?: JsonObject(emptyMap())
                    val tool = byName[name]
                    val outcome = if (tool == null) {
                        AiToolOutcome("Unknown tool '$name'", isError = true)
                    } else {
                        try {
                            tool.execute(args)
                        } catch (e: Exception) {
                            AiToolOutcome("Tool failed: ${e.message}", isError = true)
                        }
                    }
                    steps += AiToolStep(name, outcome.isError)
                    add(buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", callId)
                        put("output", outcome.text)
                    })
                }
            }
        }
        throw AiProviderException("OpenAI: tool loop exceeded $MAX_TOOL_ROUNDS rounds")
    }

    /** The visible reply: every output_text part of every message item, joined. */
    private fun outputText(output: List<JsonElement>): String = output
        .map { it.jsonObject }
        .filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
        .flatMap { it["content"]?.jsonArray.orEmpty() }
        .map { it.jsonObject }
        .filter { it["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
        .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
        .joinToString("\n")
        .trim()

    private fun errorMessage(body: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")
            ?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: body.take(300)
}
