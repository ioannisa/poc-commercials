package eu.anifantakis.commercials.server.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import kotlinx.serialization.json.putJsonObject

/**
 * Google Gemini (`generateContent`) over raw HTTP, function calling + manual
 * loop. Gemini's function-declaration schema dialect is an OpenAPI subset
 * with UPPERCASE type names - [toGeminiSchema] transforms our JSON-schema
 * fragments and drops the keywords it doesn't know.
 */
class GeminiAiProvider(
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
        val toolDefs = buildJsonObject {
            put("functionDeclarations", buildJsonArray {
                tools.forEach { t ->
                    add(buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", buildJsonObject {
                            put("type", "OBJECT")
                            put("properties", JsonObject(t.properties.mapValues { (_, v) -> v.toGeminiSchema() }))
                            if (t.required.isNotEmpty()) {
                                put("required", JsonArray(t.required.map { JsonPrimitive(it) }))
                            }
                        })
                    })
                }
            })
        }
        val contents = history.map { turn ->
            buildJsonObject {
                put("role", if (turn.role == AiChatRole.USER) "user" else "model")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", turn.text) }) })
            }
        }.toMutableList()
        val steps = mutableListOf<AiToolStep>()

        var inTok = 0L
        var outTok = 0L
        repeat(MAX_TOOL_ROUNDS) {
            val body = buildJsonObject {
                putJsonObject("systemInstruction") {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
                }
                put("contents", JsonArray(contents))
                if (tools.isNotEmpty()) put("tools", buildJsonArray { add(toolDefs) })
                putJsonObject("generationConfig") { put("maxOutputTokens", maxTokens) }
            }
            // Transport failures (timeouts, DNS, resets) become a clean 502
            // with the provider named - never an unhandled 500/406.
            val response = try {
                http.post(
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                ) {
                    header("x-goog-api-key", apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw AiProviderException("Gemini: ${e.message ?: "network failure"}", e)
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw AiProviderException("Gemini ${response.status.value}: ${errorMessage(text)}")
            }
            val root = json.parseToJsonElement(text).jsonObject
            (root["usageMetadata"] as? JsonObject)?.let { u ->
                fun n(k: String) = (u[k] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
                inTok += n("promptTokenCount")
                outTok += n("candidatesTokenCount") + n("thoughtsTokenCount")
            }
            val content = root["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject
                ?: throw AiProviderException("Gemini: empty response")
            val parts = content["parts"]?.jsonArray ?: JsonArray(emptyList())

            val functionCalls = parts.mapNotNull { it.jsonObject["functionCall"]?.jsonObject }
            if (functionCalls.isEmpty()) {
                val answer = parts
                    .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    .joinToString("\n").trim()
                return AiChatReply(answer, steps, usage = AiUsage(inTok, outTok))
            }

            contents += content   // echo the model turn (with its functionCall parts) verbatim
            val responseParts = buildJsonArray {
                for (call in functionCalls) {
                    val name = call["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val args = call["args"] as? JsonObject ?: JsonObject(emptyMap())
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
                        putJsonObject("functionResponse") {
                            put("name", name)
                            putJsonObject("response") { put("result", outcome.text) }
                        }
                    })
                }
            }
            contents += buildJsonObject {
                put("role", "user")
                put("parts", responseParts)
            }
        }
        throw AiProviderException("Gemini: tool loop exceeded $MAX_TOOL_ROUNDS rounds")
    }

    private fun errorMessage(body: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")
            ?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: body.take(300)
}

/** Keywords Gemini's OpenAPI-subset schema understands. */
private val GEMINI_SCHEMA_KEYS = setOf("type", "description", "enum", "items", "properties", "required", "nullable", "format")

/** JSON-schema fragment -> Gemini dialect: UPPERCASE types, unknown keywords dropped. */
private fun JsonElement.toGeminiSchema(): JsonElement {
    val obj = this as? JsonObject ?: return this
    return JsonObject(
        obj.filterKeys { it in GEMINI_SCHEMA_KEYS }.mapValues { (key, value) ->
            when (key) {
                "type" -> JsonPrimitive((value as? JsonPrimitive)?.contentOrNull?.uppercase() ?: "STRING")
                "items" -> value.toGeminiSchema()
                "properties" -> JsonObject((value as? JsonObject ?: JsonObject(emptyMap())).mapValues { (_, v) -> v.toGeminiSchema() })
                else -> value
            }
        }
    )
}
