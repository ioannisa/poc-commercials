package eu.anifantakis.commercials.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.LocalDate

internal val toolLogger = LoggerFactory.getLogger("eu.anifantakis.commercials.mcp")!!

/**
 * Runs a tool body OFF the transport thread ([Dispatchers.IO] - the StationDb
 * calls are blocking JDBC) and maps outcomes to an MCP result:
 * - success -> the returned content blocks,
 * - [McpToolException] -> a clean tool error with its message,
 * - anything else -> a logged, generic tool error (never leaks a stack trace).
 */
internal suspend fun runToolBlocks(name: String, block: suspend () -> List<ContentBlock>): CallToolResult =
    try {
        CallToolResult(content = withContext(Dispatchers.IO) { block() })
    } catch (e: McpToolException) {
        CallToolResult(content = listOf(TextContent(e.message ?: "error")), isError = true)
    } catch (e: Exception) {
        toolLogger.error("MCP tool '$name' failed", e)
        CallToolResult(content = listOf(TextContent("Internal error running '$name': ${e.message}")), isError = true)
    }

/** [runToolBlocks] convenience for the common case: one JSON payload as text. */
internal suspend fun runTool(name: String, block: suspend () -> JsonElement): CallToolResult =
    runToolBlocks(name) { listOf(TextContent(block().toString())) }

/** Parses an ISO `YYYY-MM-DD` date argument, or throws a clear tool error. */
internal fun parseIsoDate(value: String): LocalDate =
    try {
        LocalDate.parse(value.trim())
    } catch (e: Exception) {
        throw McpToolException("Invalid date '$value' - use YYYY-MM-DD.")
    }

/** Typed accessors over a tool call's JSON arguments, with clear missing/invalid messages. */
internal class Args(private val obj: JsonObject?) {
    private fun prim(name: String): JsonPrimitive? =
        (obj?.get(name) as? JsonPrimitive)?.takeIf { it !== JsonNull }

    fun stringOrNull(name: String): String? = prim(name)?.contentOrNull
    fun string(name: String): String = stringOrNull(name) ?: missing(name)

    fun boolOrNull(name: String): Boolean? =
        prim(name)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
    fun bool(name: String, default: Boolean): Boolean = boolOrNull(name) ?: default

    fun longOrNull(name: String): Long? =
        prim(name)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    fun long(name: String): Long = longOrNull(name) ?: missing(name)

    fun intOrNull(name: String): Int? =
        prim(name)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    fun int(name: String): Int = intOrNull(name) ?: missing(name)

    /** A required JSON array of numbers, e.g. reorder's ordered placement ids. */
    fun longList(name: String): List<Long> {
        val arr = obj?.get(name) as? JsonArray
            ?: throw McpToolException("Parameter '$name' must be an array of numbers")
        return arr.map { el ->
            (el as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
                ?: throw McpToolException("Parameter '$name' must contain only numbers")
        }
    }

    /** [longList] when present, else null (for optional array params). */
    fun longListOrNull(name: String): List<Long>? =
        obj?.get(name)?.takeIf { it !is JsonNull }?.let { longList(name) }

    private fun missing(name: String): Nothing =
        throw McpToolException("Missing or invalid parameter '$name'")
}

/** A standard dry-run payload: [detail] merged under a not-written notice. */
internal fun dryRun(action: String, detail: JsonObject): JsonObject = buildJsonObject {
    put("dryRun", true)
    put("action", action)
    put("note", "Not written. Re-call with confirm=true to apply.")
    for ((k, v) in detail) put(k, v)
}

internal val CallToolRequest.args: Args get() = Args(params.arguments)

/**
 * Builds a tool input schema (a JSON-Schema `object`) from the given
 * [properties], marking [required] ones. Pass property definitions via [prop].
 */
internal fun inputSchema(
    required: List<String> = emptyList(),
    properties: JsonObjectBuilder.() -> Unit,
): ToolSchema = ToolSchema(
    properties = buildJsonObject(properties),
    required = required.ifEmpty { null },
)

/** Declares a single scalar JSON-Schema property (string/integer/boolean/...). */
internal fun JsonObjectBuilder.prop(name: String, type: String, description: String, enum: List<String>? = null) {
    put(name, buildJsonObject {
        put("type", type)
        put("description", description)
        if (enum != null) put("enum", buildJsonArray { enum.forEach { add(it) } })
    })
}

/** Declares an array JSON-Schema property whose items are [itemType]. */
internal fun JsonObjectBuilder.propArray(name: String, itemType: String, description: String) {
    put(name, buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject { put("type", itemType) })
    })
}
