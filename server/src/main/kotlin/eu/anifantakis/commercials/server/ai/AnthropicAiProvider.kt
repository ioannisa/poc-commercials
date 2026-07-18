package eu.anifantakis.commercials.server.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Claude via the official Anthropic Java SDK. Manual agentic loop (the SDK's
 * tool runner wants annotated classes; our tools are dynamic MCP bridges).
 * Sonnet 5 runs adaptive thinking when `thinking` is omitted - thinking
 * blocks that come back are echoed into the follow-up turn unchanged, as the
 * API requires.
 */
class AnthropicAiProvider(apiKey: String) : AiChatProvider {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    override suspend fun chat(
        system: String,
        history: List<AiChatTurn>,
        tools: List<AiBridgedTool>,
        model: String,
        maxTokens: Int,
    ): AiChatReply = withContext(Dispatchers.IO) {
        val byName = tools.associateBy { it.name }
        val sdkTools = tools.map { it.toSdkTool() }
        val messages = history.map { turn ->
            MessageParam.builder()
                .role(if (turn.role == AiChatRole.USER) MessageParam.Role.USER else MessageParam.Role.ASSISTANT)
                .content(turn.text)
                .build()
        }.toMutableList()
        val steps = mutableListOf<AiToolStep>()
        var inTok = 0L
        var outTok = 0L

        repeat(MAX_TOOL_ROUNDS) {
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens.toLong())
                .system(system)
                .apply {
                    sdkTools.forEach { addTool(it) }
                    messages.forEach { addMessage(it) }
                }
                .build()
            val response = try {
                client.messages().create(params)
            } catch (e: Exception) {
                throw AiProviderException("Anthropic: ${e.message}", e)
            }

            inTok += response.usage().inputTokens()
            outTok += response.usage().outputTokens()
            val stop = response.stopReason().orElse(null)
            if (stop != StopReason.TOOL_USE) {
                val text = response.content()
                    .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
                    .joinToString("\n").trim()
                return@withContext AiChatReply(text, steps, usage = AiUsage(inTok, outTok))
            }

            // Echo the assistant turn back verbatim (text + thinking + tool_use),
            // then answer every tool_use with a matching tool_result.
            data class PendingUse(val id: String, val name: String, val args: JsonObject)
            val assistantBlocks = mutableListOf<ContentBlockParam>()
            val pending = mutableListOf<PendingUse>()
            response.content().forEach { block ->
                block.text().ifPresent { t ->
                    assistantBlocks += ContentBlockParam.ofText(TextBlockParam.builder().text(t.text()).build())
                }
                block.thinking().ifPresent { t ->
                    assistantBlocks += ContentBlockParam.ofThinking(
                        ThinkingBlockParam.builder().thinking(t.thinking()).signature(t.signature()).build()
                    )
                }
                block.toolUse().ifPresent { use ->
                    assistantBlocks += ContentBlockParam.ofToolUse(
                        ToolUseBlockParam.builder().id(use.id()).name(use.name()).input(use._input()).build()
                    )
                    pending += PendingUse(
                        id = use.id(),
                        name = use.name(),
                        args = use._input().toKotlinx() as? JsonObject ?: JsonObject(emptyMap()),
                    )
                }
            }
            val toolResults = mutableListOf<ContentBlockParam>()
            for (use in pending) {
                val tool = byName[use.name]
                val outcome = if (tool == null) {
                    AiToolOutcome("Unknown tool '${use.name}'", isError = true)
                } else {
                    try {
                        tool.execute(use.args)
                    } catch (e: Exception) {
                        AiToolOutcome("Tool failed: ${e.message}", isError = true)
                    }
                }
                steps += AiToolStep(use.name, outcome.isError)
                toolResults += ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder()
                        .toolUseId(use.id)
                        .content(outcome.text)
                        .isError(outcome.isError)
                        .build()
                )
            }
            messages += MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(assistantBlocks)
                .build()
            messages += MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(toolResults)
                .build()
        }
        throw AiProviderException("Anthropic: tool loop exceeded $MAX_TOOL_ROUNDS rounds")
    }
}

private fun AiBridgedTool.toSdkTool(): Tool =
    Tool.builder()
        .name(name)
        .description(description)
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(
                    Tool.InputSchema.Properties.builder().apply {
                        properties.forEach { (key, value) -> putAdditionalProperty(key, value.toJsonValue()) }
                    }.build()
                )
                .apply { if (required.isNotEmpty()) required(required) }
                .build()
        )
        .build()

/** kotlinx JsonElement -> SDK JsonValue (via plain Java maps/lists/scalars). */
private fun JsonElement.toJsonValue(): JsonValue = JsonValue.from(toPlain())

private fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    is JsonArray -> map { it.toPlain() }
    is JsonObject -> entries.associate { (k, v) -> k to v.toPlain() }
}

/** SDK JsonValue -> kotlinx JsonElement (walks the optional accessors). */
private fun JsonValue.toKotlinx(): JsonElement {
    asObject().orElse(null)?.let { map ->
        return JsonObject(map.entries.associate { (k, v) -> k to v.toKotlinx() })
    }
    asArray().orElse(null)?.let { list ->
        return JsonArray(list.map { it.toKotlinx() })
    }
    asString().orElse(null)?.let { return JsonPrimitive(it) }
    asNumber().orElse(null)?.let { return JsonPrimitive(it) }
    asBoolean().orElse(null)?.let { return JsonPrimitive(it) }
    return JsonNull
}
