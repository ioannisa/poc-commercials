package eu.anifantakis.commercials.server.ai

import eu.anifantakis.commercials.mcp.McpCaller
import eu.anifantakis.commercials.mcp.McpToolServices
import eu.anifantakis.commercials.mcp.tools.ALL_MCP_TOOLS
import eu.anifantakis.commercials.mcp.tools.ToolContext
import eu.anifantakis.commercials.server.auth.AuthUser
import eu.anifantakis.commercials.server.stations.AiChatConfig
import eu.anifantakis.commercials.server.stations.AiProviderCatalogEntry
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * A client-named provider/model that is not in the server.yaml catalog - the
 * whitelist is the operator's COST control (clients must never coax the server
 * into calling an arbitrary, pricier model). Maps to 400, not 502.
 */
class AiSelectionException(message: String) : IllegalArgumentException(message)

/**
 * The in-app AI assistant organizer: resolves the caller's provider/model pick
 * against the server.yaml catalog (default = first entry), bridges the MCP
 * tool registry (READ-ONLY subset - Phase 1 has no mutations and no PDF
 * report tools, whose base64 payloads would flood the model), builds the
 * per-user system prompt, and runs one chat request as the calling user - the
 * same [McpCaller] scoping every MCP transport gets.
 */
class AiChatService(
    private val registry: StationRegistry,
    private val services: McpToolServices,
) {
    /** The configured providers, default first - what login/session hand to clients. */
    val catalog: List<AiProviderCatalogEntry> get() = registry.aiChatProviders

    val enabled: Boolean get() = catalog.isNotEmpty()

    /** Providers that need raw HTTP share one client; model calls can run long. */
    private val http by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 180_000
                connectTimeoutMillis = 15_000
            }
        }
    }

    /** One adapter per configured provider, created on first use (keys are boot-fixed). */
    private val providers = ConcurrentHashMap<String, AiChatProvider>()

    private fun provider(entry: AiProviderCatalogEntry): AiChatProvider = providers.getOrPut(entry.id) {
        when (entry.id) {
            AiChatConfig.PROVIDER_ANTHROPIC -> AnthropicAiProvider(entry.apiKey)
            AiChatConfig.PROVIDER_OPENAI -> OpenAiAiProvider(entry.apiKey, http)
            AiChatConfig.PROVIDER_GEMINI -> GeminiAiProvider(entry.apiKey, http)
            else -> throw AiProviderException("Unknown ai provider '${entry.id}' in server.yaml")
        }
    }

    /**
     * One chat request as [user]. [providerId]/[modelId] are the client's
     * dropdown picks - both validated against the catalog ([AiSelectionException]
     * on anything outside it), both optional (null = the default: first
     * configured provider, its first model). [stationId] is the app's ACTIVE
     * station (the `?station=` every client request carries): when present and
     * granted, the model is pinned to it - tools get this id and the user is
     * never asked "which station?".
     */
    suspend fun chat(
        user: AuthUser,
        history: List<AiChatTurn>,
        providerId: String?,
        modelId: String?,
        stationId: String? = null,
    ): AiChatReply {
        val entries = catalog
        if (entries.isEmpty()) throw AiProviderException("AI assistant is not configured (server.yaml ai:)")
        val entry = providerId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?.let { id ->
                entries.firstOrNull { it.id == id }
                    ?: throw AiSelectionException("Unknown AI provider '$id' - configured: ${entries.map { it.id }}")
            }
            ?: entries.first()
        val model = modelId?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { m ->
                entry.models.firstOrNull { it == m }
                    ?: throw AiSelectionException("Model '$m' is not offered for provider '${entry.id}' - allowed: ${entry.models}")
            }
            ?: entry.models.first()
        // A station the caller has no grant on is a client bug (or a revoked
        // grant racing the UI) - refuse loudly rather than answer unscoped.
        val activeStation = stationId?.let { id ->
            if (!user.isAdmin && user.grants.none { it.stationId == id }) {
                throw AiSelectionException("No access to station '$id'")
            }
            id
        }
        return provider(entry).chat(
            system = systemPrompt(user, activeStation),
            history = history,
            tools = bridgedTools(user),
            model = model,
            maxTokens = registry.aiChatMaxTokens,
        )
    }

    private fun bridgedTools(user: AuthUser): List<AiBridgedTool> {
        val ctx = ToolContext(McpCaller.of(user), services)
        return ALL_MCP_TOOLS
            .filter { !it.mutating && it.name !in EXCLUDED_TOOLS }
            .map { tool ->
                AiBridgedTool(
                    name = tool.name,
                    description = tool.description,
                    properties = tool.inputSchema.properties ?: JsonObject(emptyMap()),
                    required = tool.inputSchema.required ?: emptyList(),
                    execute = { args ->
                        val result = tool.handle(ctx, CallToolRequest(CallToolRequestParams(tool.name, args)))
                        val text = result.content
                            .filterIsInstance<TextContent>()
                            .joinToString("\n") { it.text }
                            .trim()
                        AiToolOutcome(text.ifBlank { "(empty result)" }, result.isError == true)
                    },
                )
            }
    }

    private fun systemPrompt(user: AuthUser, activeStationId: String? = null): String {
        val stations = user.grants.joinToString("\n") { grant ->
            val name = registry.config(grant.stationId)?.name ?: grant.stationId
            "- ${grant.stationId} (\"$name\") - role ${grant.role.name}" +
                (grant.clientCode?.let { ", restricted to customer code $it" } ?: "")
        }.ifBlank { "- (super administrator: every hosted station)" }
        // The app pins the chat to its ACTIVE station; without one (raw API
        // callers), the model falls back to asking when ambiguous.
        val stationRule = if (activeStationId != null) {
            val name = registry.config(activeStationId)?.name ?: activeStationId
            """
            The application is currently working on station "$name" (id: $activeStationId) - the
            ACTIVE station. Scope EVERYTHING to it: pass station id "$activeStationId" to every
            tool call and NEVER ask which station is meant. If the user explicitly asks about a
            different station, do not answer for it - explain that this chat follows the
            application's active station and they should switch station in the app first.
            """.trimIndent()
        } else {
            """
            Station ids (not display names) go into tool arguments. When a question names no
            station and the user has exactly one, use it; otherwise ask which station.
            """.trimIndent()
        }
        return """
            You are the built-in AI assistant of Commercials Manager, an application that plans
            TV/radio commercial schedules (breaks, spots, contracts, customers).

            The user is ${user.displayName} (username ${user.username})${if (user.isAdmin) ", the super administrator" else ""}.
            Their station access:
            $stations

            Today is ${LocalDate.now()} and the local time right now is ${currentTimeHHmm()} -
            "next"/"previous" in questions is relative to this moment unless stated otherwise.

            Use the available tools to answer questions about schedules, breaks, spots, contracts
            and customers.
            $stationRule

            You currently have READ-ONLY access: you cannot add, move or delete placements or send
            emails. If asked to change something, explain politely that modifications from the chat
            are not enabled yet and describe how to do it in the application instead.

            Answer in the language the user writes in. Be concise and factual; prefer short tables
            or lists for schedule data. Never invent data - if a tool returns nothing, say so.
        """.trimIndent()
    }

    private companion object {
        /** Read tools whose output is a base64 PDF - useless and enormous in a chat context. */
        val EXCLUDED_TOOLS = setOf("generate_break_report", "generate_day_report")

        fun currentTimeHHmm(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
    }
}
