package eu.anifantakis.commercials.server.ai

import eu.anifantakis.commercials.mcp.McpCaller
import eu.anifantakis.commercials.mcp.McpToolServices
import eu.anifantakis.commercials.mcp.tools.ALL_MCP_TOOLS
import eu.anifantakis.commercials.mcp.tools.ToolContext
import eu.anifantakis.commercials.server.auth.AuthUser
import eu.anifantakis.commercials.server.auth.UserRole
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
        // Mutations need: the operator's yaml opt-in, a pinned station, and
        // STAFF role on it (the same rule the tools enforce at execution).
        val mutations = registry.aiChatMutations && activeStation != null && canEdit(user, activeStation)
        val proposals = mutableListOf<AiProposal>()
        val clientActions = mutableListOf<AiClientAction>()
        val tools = buildList {
            addAll(bridgedTools(user, if (mutations) proposals else null))
            // Station switching is a pure UI action - only meaningful when an
            // app (not a raw API caller) is on the other end of the pin.
            if (activeStation != null) add(switchStationTool(user, activeStation, clientActions))
        }
        val reply = provider(entry).chat(
            system = systemPrompt(user, activeStation, mutations),
            history = history,
            tools = tools,
            model = model,
            maxTokens = registry.aiChatMaxTokens,
        )
        return reply.copy(proposals = proposals.toList(), clientActions = clientActions.toList())
    }

    /**
     * The one CLIENT-side tool: ask the app to change its ACTIVE station. No
     * data is touched server-side - a successful call queues an
     * [AiClientAction] the app executes on receipt (grant-checked here AND
     * again client-side). The tool result tells the model it may already use
     * the new station's id for the REST of this turn - the prompt pin refers
     * to the station the app is switching to anyway.
     */
    private fun switchStationTool(
        user: AuthUser,
        activeStation: String,
        sink: MutableList<AiClientAction>,
    ): AiBridgedTool = AiBridgedTool(
        name = "switch_station",
        description = "Switch the application's ACTIVE station. Use this when the user asks to work on " +
            "(or about) a different station they have access to. The application performs the switch " +
            "the moment this reply reaches it - no confirmation card is involved.",
        properties = kotlinx.serialization.json.buildJsonObject {
            put(
                "station",
                kotlinx.serialization.json.buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The station id to switch to (e.g. crete-tv)."))
                },
            )
        },
        required = listOf("station"),
        execute = { args ->
            val target = (args["station"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            when {
                target.isEmpty() -> AiToolOutcome("Parameter 'station' is required.", isError = true)
                registry.config(target) == null -> AiToolOutcome("Unknown station '$target'.", isError = true)
                !user.isAdmin && user.grants.none { it.stationId == target } ->
                    AiToolOutcome("The user has no access to station '$target'.", isError = true)
                target == activeStation ->
                    AiToolOutcome("Station '$target' is already the active station.", isError = false)
                else -> {
                    sink += AiClientAction("switch_station", JsonObject(mapOf("station" to JsonPrimitive(target))))
                    AiToolOutcome(
                        "The application switches its active station to '$target' as soon as this reply " +
                            "is delivered. For the rest of THIS turn you may already pass station id " +
                            "'$target' to tools.",
                        isError = false,
                    )
                }
            }
        },
    )

    /** Staff (NORMAL_USER) on [stationId] - mirrors McpToolServices.requireStaff. */
    private fun canEdit(user: AuthUser, stationId: String): Boolean =
        user.isAdmin || user.grants.any { it.stationId == stationId && it.role == UserRole.NORMAL_USER }

    /**
     * The user pressed a card's APPROVE button: replay the proposed call with
     * `confirm=true`. Everything is re-validated on this path - the tool must
     * be a bridgeable mutation, mutations must (still) be enabled, and the
     * action must target the app's active station; the tool itself re-checks
     * the staff role and re-validates the data, so a proposal gone stale
     * (spot deleted meanwhile) fails honestly instead of half-applying.
     */
    suspend fun executeProposal(
        user: AuthUser,
        stationId: String?,
        toolName: String,
        arguments: JsonObject,
    ): AiToolOutcome {
        if (!registry.aiChatMutations) throw AiSelectionException("Chat mutations are disabled (server.yaml ai.mutations)")
        val tool = ALL_MCP_TOOLS.firstOrNull { it.name == toolName && it.mutating && it.name !in EXCLUDED_TOOLS }
            ?: throw AiSelectionException("Unknown mutation tool '$toolName'")
        stationId?.let { active ->
            if (!canEdit(user, active)) throw AiSelectionException("Requires full access on station '$active'")
            val target = (arguments["station"] as? JsonPrimitive)?.contentOrNull
            if (target != null && target != active) {
                throw AiSelectionException("Action targets station '$target' but the app is on '$active'")
            }
        }
        val confirmed = JsonObject(arguments.filterKeys { it != "confirm" } + ("confirm" to JsonPrimitive(true)))
        val ctx = ToolContext(McpCaller.of(user), services)
        val result = tool.handle(ctx, CallToolRequest(CallToolRequestParams(tool.name, confirmed)))
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }.trim()
        return AiToolOutcome(text.ifBlank { "(empty result)" }, result.isError == true)
    }

    /**
     * The tool bridge. Read tools pass through; when [proposals] is non-null
     * the MUTATING tools ride along too - as FORCED DRY-RUNS: any `confirm`
     * the model sends is stripped, the tool's preview runs (validating the
     * data), and a successful preview is collected as an [AiProposal] card
     * for the user to approve. The model can therefore never perform a write.
     */
    private fun bridgedTools(user: AuthUser, proposals: MutableList<AiProposal>?): List<AiBridgedTool> {
        val ctx = ToolContext(McpCaller.of(user), services)

        suspend fun run(tool: eu.anifantakis.commercials.mcp.tools.McpTool, args: JsonObject): AiToolOutcome {
            val result = tool.handle(ctx, CallToolRequest(CallToolRequestParams(tool.name, args)))
            val text = result.content
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text }
                .trim()
            return AiToolOutcome(text.ifBlank { "(empty result)" }, result.isError == true)
        }

        return ALL_MCP_TOOLS
            .filter { it.name !in EXCLUDED_TOOLS && (!it.mutating || proposals != null) }
            .map { tool ->
                AiBridgedTool(
                    name = tool.name,
                    description = tool.description,
                    properties = tool.inputSchema.properties ?: JsonObject(emptyMap()),
                    required = tool.inputSchema.required ?: emptyList(),
                    execute = { rawArgs ->
                        if (tool.mutating && proposals != null) {
                            val args = JsonObject(rawArgs.filterKeys { it != "confirm" })
                            val outcome = run(tool, args)
                            if (outcome.isError) {
                                outcome
                            } else {
                                proposals += AiProposal(tool.name, args, outcome.text)
                                AiToolOutcome(
                                    outcome.text + "\n[PREPARED, NOT PERFORMED: the user now sees " +
                                        "an approve/cancel card for this action. Do not call this tool " +
                                        "again for the same action - tell the user to review the card.]",
                                    isError = false,
                                )
                            }
                        } else {
                            run(tool, rawArgs)
                        }
                    },
                )
            }
    }

    private fun systemPrompt(user: AuthUser, activeStationId: String? = null, mutations: Boolean = false): String {
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
            tool call and NEVER ask which station is meant. If the user wants to work on (or asks
            about) a DIFFERENT station they have access to, call the switch_station tool - the
            application follows and the conversation continues on that station. Never answer
            about another station without switching first.
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

            ${if (mutations) MUTATIONS_RULE else READ_ONLY_RULE}

            Answer in the language the user writes in. Be concise and factual. Your answers are
            rendered as GitHub-flavoured Markdown: use markdown tables for tabular data (schedule
            lists, spot breakdowns) and inline code for ids/codes. Never invent data - if a tool
            returns nothing, say so.
        """.trimIndent()
    }

    private companion object {
        /** Read tools whose output is a base64 PDF - useless and enormous in a chat context. */
        val EXCLUDED_TOOLS = setOf("generate_break_report", "generate_day_report")

        val READ_ONLY_RULE = """
            You currently have READ-ONLY access: you cannot add, move or delete placements or send
            emails. If asked to change something, explain politely that modifications from the chat
            are not enabled yet and describe how to do it in the application instead.
        """.trimIndent()

        val MUTATIONS_RULE = """
            You can PREPARE schedule changes (add/delete/reorder placements, send schedule emails)
            with the mutation tools - but you can never perform them. Calling a mutation tool runs
            a validated DRY-RUN and queues a confirmation card in the app; ONLY the user's approval
            of that card executes the action. Call the tool once per intended action (never set
            'confirm'), then tell the user to review and approve the card. If the dry-run returns
            an error, fix the arguments and try again. Never claim an action happened - it happens
            only after the user approves, and you will see a confirmation note in the conversation
            when it does.
        """.trimIndent()

        fun currentTimeHHmm(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
    }
}
