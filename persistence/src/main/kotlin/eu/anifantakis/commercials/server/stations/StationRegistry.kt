package eu.anifantakis.commercials.server.stations

import com.charleskorn.kaml.Yaml
import eu.anifantakis.commercials.server.scheduler.GroupDb
import eu.anifantakis.commercials.server.scheduler.StationDb
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Provided
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Built-in connection-pool ceilings when nothing is set in server.yaml. */
const val DEFAULT_CENTRAL_MAX_POOL = 10

/**
 * Built-in ceiling for a GROUP's pool. One pool now serves every station of the
 * group (they share the database), so it is higher than the old per-station 5.
 */
const val DEFAULT_GROUP_MAX_POOL = 10

/**
 * Effective HikariCP `maximumPoolSize`: a per-connection [override] wins, then
 * the file-wide [global] default, then the [builtinDefault].
 */
internal fun resolveMaxPoolSize(override: Int?, global: Int?, builtinDefault: Int): Int =
    override ?: global ?: builtinDefault

/**
 * A MySQL connection target (full JDBC URL + credentials, plaintext by
 * design: server.yaml is server-side deployment config).
 *
 * @param maxPoolSize optional HikariCP pool ceiling override for this
 *        connection; falls back to the file-wide default, then the built-in.
 */
@Serializable
data class DbConnectionConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maxPoolSize: Int? = null,
)

/**
 * A hosted GROUP: ONE database shared by the group's stations.
 *
 * This mirrors the legacy world exactly. The original app kept one MySQL
 * database per company group and separated its TV station from its radio
 * station with a `forTV` 0/1 column - customers, contracts and contract lines
 * were stored ONCE and shared, because one contract genuinely sells on both
 * media (Ανυφαντάκης buys 1 TV spot and 2 radio spots on the same contract).
 * Our `station_id` is that flag, generalized past two values.
 *
 * So: group-scoped tables (customers, contracts, contract_lines, spot_types)
 * live here once; station-scoped tables (spots, programs, break_slots, ...)
 * carry `station_id`. One legacy dump ⇒ one group database ⇒ N stations.
 *
 * @param maxPoolSize optional per-group HikariCP ceiling. NOTE it is per
 *        DATABASE, not per station: every station of the group draws from it.
 */
@Serializable
data class GroupConfig(
    /** Stable key, e.g. "crete-group". Used in migration and admin APIs. */
    val id: String,
    /** Display name, e.g. "Κρητική Ραδιοτηλεόραση". */
    val name: String? = null,
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maxPoolSize: Int? = null,
    /** Group-wide SMTP default; a station block may still override it. */
    val smtp: SmtpConfig? = null,
    /** The stations sharing this database. 0..n. */
    val stations: List<StationConfig> = emptyList(),
)

/**
 * One hosted station (TV or radio) INSIDE a group. It carries no connection
 * details of its own - it is a `station_id` in its group's database.
 */
@Serializable
data class StationConfig(
    /** Stable key used in grants and API calls, e.g. "crete-tv". */
    val id: String,
    /** Display name shown to users, e.g. "Crete TV". */
    val name: String,
    /**
     * Absolute path to this station's logo image, printed on its reports.
     * Absent (or unreadable) means NO logo: the template prints a plain "LOGO"
     * placeholder rather than failing, so a bad path costs a logo, not a report.
     *
     * It is a path on the machine that RENDERS, and reports render in two
     * places: in-process on desktop, and on the server for every other client.
     * The server injects this value itself and IGNORES whatever a client sends
     * (see reportRoutes) - a client-chosen path would be a file-read primitive
     * on the server. The desktop reads it straight from the session.
     */
    val logo: String? = null,
    /**
     * From this time of day on, the hourly / half-hourly views print their grid
     * rows even when EMPTY; before it, only times that actually have a break
     * appear. `"HH:mm"`, default `"00:00"` (a full 24-hour grid).
     *
     * This is the "Προβολή κάθε: 1 Ώρα / Μισή Ώρα" behaviour of the original app,
     * and it exists because a station's night is nearly empty: Crete TV starts at
     * 07:00, so its grid runs 00:05, 00:30 … 04:00 (real breaks, the tail of the
     * previous night) and then jumps to a printed 07:00, 07:30, 08:00 … even
     * though nothing airs there. Without it the operator would page through
     * fourteen blank night rows to reach the working day.
     *
     * A break is never hidden by this: it only decides where EMPTY rows are drawn.
     */
    val emptyRowsFrom: String = "00:00",
    /** Overrides the group's (and the file's) smtp block for this station. */
    val smtp: SmtpConfig? = null,
)

/**
 * SMTP settings for customer schedule emails (≙ legacy `emailsetup`).
 * Optional: without one, email endpoints answer with a clear 400. Resolution
 * is station → group → file-wide (each outlet traditionally sent from its own
 * address, e.g. dmaria@cretetv.gr).
 */
@Serializable
data class SmtpConfig(
    val host: String,
    val port: Int = 587,
    val username: String? = null,
    val password: String? = null,
    val from: String,
    val startTls: Boolean = true,
)

/**
 * The break-glass administrator account, deliberately PLAINTEXT in
 * server.yaml (same threat model as the DB credentials around it: whoever
 * reads this file already owns the databases). Only the server config holds
 * the plaintext - the database stores a PBKDF2 hash of it, re-synced at every
 * boot, so rotating it is "edit YAML, restart". This account manages users
 * and can never be locked out: the YAML itself is its recovery mechanism.
 */
@Serializable
data class SuperAdminConfig(
    val username: String,
    val password: String,
    val displayName: String = "Super Administrator",
)

/**
 * Bearer-token session policy (server.yaml `session:` block).
 *
 * - [expiration] `false` = tokens NEVER expire (revoked only by logout or a
 *   password change) - the original behaviour. `true` = a token dies after
 *   [days] of the relevant window.
 * - [days] the token lifetime in days when [expiration] is on.
 * - [sliding] `true` = the window slides forward on use (an idle timeout: an
 *   active user is never logged out mid-work); `false` = a fixed window from
 *   login (hard logout [days] after login regardless of activity).
 *
 * Defaults: expiration on, 90 days, sliding - a working session is never
 * interrupted, but a machine left logged in and untouched for 90 days lapses.
 */
@Serializable
data class SessionConfig(
    val expiration: Boolean = true,
    val days: Int = 90,
    val sliding: Boolean = true,
)

/**
 * The whole hosting layout:
 * - [central] is MANDATORY and STANDALONE: the server's own schema
 *   (users, tokens, per-station grants - e.g. `commercials_central`).
 *   It must never double as a group schema.
 * - [superAdmin] is MANDATORY: the config-managed administrator (see
 *   [SuperAdminConfig]). Validated non-null at load; use [admin].
 * - [groups] is 0..n: a server may host no groups at all (users can log in but
 *   have nothing to select) or many. Each group is ONE database holding 0..n
 *   stations - see [GroupConfig].
 * - [maxPoolSize] is the file-wide default HikariCP pool ceiling applied to
 *   central and every group unless they override it. When null, the built-in
 *   defaults apply (central 10, group 10). Tune this against the MySQL
 *   server's `max_connections` as the number of groups grows.
 */
@Serializable
data class HostingConfig(
    val central: DbConnectionConfig,
    val superAdmin: SuperAdminConfig? = null,
    val groups: List<GroupConfig> = emptyList(),
    val maxPoolSize: Int? = null,
    /** File-wide SMTP default for customer emails (groups/stations may override). */
    val smtp: SmtpConfig? = null,
    /** Bearer-token lifetime policy. Defaults to expiration on / 90 days / sliding. */
    val session: SessionConfig = SessionConfig(),
    /**
     * TEST-ONLY safety valve. When set, EVERY outgoing customer email is
     * delivered to THIS address instead of the customer's - so a test run can
     * never reach a real customer. The intended recipient is preserved in the
     * subject (`[TEST -> original]`) and in the audit log. Leave it UNSET in
     * production; a stray value here silently swallows every real send.
     */
    val emailRedirectTo: String? = null,
    /**
     * Hostnames the MCP SSE endpoint (`/mcp`) accepts in the `Host` header - the
     * SDK's DNS-rebinding guard. LEAVE UNSET for local dev (the SDK defaults to
     * localhost). For a remote deployment, list the server's public host(s) so
     * clients can reach `/mcp`, e.g. `["mcp.example.gr"]`. The API is
     * bearer-authenticated, so this is about REACHABILITY, not the cookie attack
     * the guard mitigates.
     */
    val mcpAllowedHosts: List<String>? = null,
    /**
     * Serve the interactive OpenAPI/Swagger UI at `/swagger` (default false).
     * A per-deployment toggle, independent of developmentMode. When true the
     * API SHAPE (incl. admin routes) is browsable by anyone who can reach the
     * server; executing authenticated routes still requires a bearer token.
     */
    val swagger: Boolean = false,
    /**
     * In-app AI assistant. UNSET = feature off (the client hides the chat
     * entry). The server holds the provider API key and proxies every chat -
     * the key never reaches a client.
     */
    val ai: AiChatConfig? = null,

    /**
     * Public origin of this server (e.g. `"https://mcp.example.gr"`) - the
     * OAuth 2.1 `issuer`. Setting it MOUNTS the OAuth endpoints (under
     * `/oauth` and the `/.well-known` discovery documents) and the 401
     * challenge header that native MCP connectors (claude.ai, ChatGPT,
     * Gemini, ...) need. LEAVE UNSET to keep OAuth off entirely - PAT access
     * still works. For local testing set `"http://localhost:8080"`.
     * No trailing slash.
     */
    val publicBaseUrl: String? = null,
    /**
     * Set true ONLY when a reverse proxy terminates TLS in front of this
     * server: installs XForwardedHeaders so rate limiting and logs see the
     * real client IP instead of the proxy's. NEVER enable it without a proxy -
     * forwarding headers are client-spoofable when nothing strips them.
     */
    val behindReverseProxy: Boolean = false,
) {
    /** The super admin, guaranteed by [loadHostingConfig]'s validation. */
    val admin: SuperAdminConfig get() = requireNotNull(superAdmin) { "superAdmin missing - config not loaded via loadHostingConfig?" }

    /** Every hosted station, flattened across groups (their ids are globally unique). */
    val stations: List<StationConfig> get() = groups.flatMap { it.stations }
}

/**
 * Loads the hosting layout from server.yaml (path via `server.config`
 * system property or `COMMERCIALS_SERVER` env, default `./server.yaml`).
 *
 * The file - and its `central` block - are REQUIRED: the server cannot run
 * without its own schema. Group entries are optional.
 */
fun loadHostingConfig(): HostingConfig {
    val log = LoggerFactory.getLogger("StationRegistry")
    val explicit = System.getProperty("server.config") ?: System.getenv("COMMERCIALS_SERVER")
    val file = File(explicit ?: "server.yaml")

    require(file.exists()) {
        "Required config '${file.path}' not found. It must define the mandatory 'central' database " +
            "(users/tokens/grants) and optionally 0..n hosted groups. " +
            "Override the path with -Dserver.config=<path> or COMMERCIALS_SERVER."
    }

    val text = file.readText()

    // The flat one-schema-per-station layout is gone: stations now live inside
    // a group that owns the database. Fail with the recipe rather than with a
    // kaml "unknown property" a few lines down.
    require(!Regex("^stations:", RegexOption.MULTILINE).containsMatchIn(text)) {
        "'${file.path}' still uses the top-level 'stations:' list (one database per station). " +
            "Stations now live inside a 'groups:' entry that owns the database - customers and " +
            "contracts are shared by the group's stations. Convert:\n" +
            "  groups:\n" +
            "    - id: my-group\n" +
            "      name: \"My Group\"\n" +
            "      jdbcUrl: \"<the station's old jdbcUrl>\"\n" +
            "      username: root\n" +
            "      password: ...\n" +
            "      stations:\n" +
            "        - id: <the station's old id>\n" +
            "          name: \"...\""
    }

    val parsed = Yaml.default.decodeFromString(HostingConfig.serializer(), text)

    requireNotNull(parsed.superAdmin) {
        "'${file.path}' must define the mandatory 'superAdmin' block (username + password) - " +
            "the config-managed administrator account that creates users and resets passwords."
    }
    require(parsed.superAdmin.username.isNotBlank() && parsed.superAdmin.password.isNotBlank()) {
        "superAdmin.username and superAdmin.password must be non-blank in '${file.path}'"
    }

    require(!parsed.session.expiration || parsed.session.days > 0) {
        "session.days must be > 0 in '${file.path}' when session.expiration is enabled (was ${parsed.session.days})"
    }

    // publicBaseUrl is the OAuth issuer - it must be an http(s) origin, and a
    // trailing slash would leak into every derived endpoint URL (trimmed in
    // StationRegistry, but reject junk outright here).
    parsed.publicBaseUrl?.let { url ->
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "publicBaseUrl must start with http:// or https:// in '${file.path}' (was '$url')"
        }
    }

    parsed.ai?.let { ai ->
        // A keyed openai/gemini block without models would put an EMPTY model
        // dropdown in front of the user - reject at boot, not at first chat.
        listOf(
            AiChatConfig.PROVIDER_OPENAI to ai.openai,
            AiChatConfig.PROVIDER_GEMINI to ai.gemini,
        ).forEach { (id, block) ->
            require(block == null || block.apiKey.isBlank() || block.models.any { it.isNotBlank() }) {
                "ai.$id.models must list at least one model in '${file.path}' - " +
                    "$id has no safe baked-in default (their catalogs move too fast)"
            }
        }
        val configured = ai.configuredProviders()
        ai.provider?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { def ->
            require(configured.any { it.id == def }) {
                "ai.provider is '$def' in '${file.path}' but that provider holds no apiKey - " +
                    "configured providers: ${configured.map { it.id }.ifEmpty { listOf("none") }}"
            }
        }
        require(ai.maxTokens > 0) { "ai.maxTokens must be > 0 in '${file.path}' (was ${ai.maxTokens})" }
    }

    val duplicateGroups = parsed.groups.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicateGroups.isEmpty()) { "Duplicate group ids in '${file.path}': $duplicateGroups" }

    // Station ids are the grant key and the ?station= value, so they must be
    // unique across the WHOLE file, not just within their group.
    val duplicateStations = parsed.stations.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicateStations.isEmpty()) {
        "Duplicate station ids in '${file.path}': $duplicateStations (station ids must be unique " +
            "across ALL groups - they are the key of a user's grant)"
    }

    // Any specified pool ceiling must be positive
    val declaredPoolSizes = buildList {
        add("(global) maxPoolSize" to parsed.maxPoolSize)
        add("central.maxPoolSize" to parsed.central.maxPoolSize)
        parsed.groups.forEach { add("group '${it.id}' maxPoolSize" to it.maxPoolSize) }
    }
    declaredPoolSizes.forEach { (who, size) ->
        require(size == null || size > 0) { "$who must be > 0 in '${file.path}' (was $size)" }
    }

    // The central schema is standalone by contract - reject configs that point
    // a group at the same database as central.
    val centralTarget = databaseTarget(parsed.central.jdbcUrl)
    val clashing = parsed.groups.filter { databaseTarget(it.jdbcUrl) == centralTarget }
    require(clashing.isEmpty()) {
        "Group(s) ${clashing.map { it.id }} use the CENTRAL database ($centralTarget) as their schema. " +
            "The central schema is standalone and must not double as a group schema."
    }

    // Two groups on one database would silently share customers and contracts.
    val duplicateTargets = parsed.groups.groupBy { databaseTarget(it.jdbcUrl) }
        .filterValues { it.size > 1 }
    require(duplicateTargets.isEmpty()) {
        "Group(s) ${duplicateTargets.values.flatten().map { it.id }} point at the same database " +
            "(${duplicateTargets.keys}). A group OWNS its database - sharing one would merge their " +
            "customers and contracts."
    }

    val centralPool = resolveMaxPoolSize(parsed.central.maxPoolSize, parsed.maxPoolSize, DEFAULT_CENTRAL_MAX_POOL)
    val sessionPolicy = with(parsed.session) {
        if (!expiration) "session: tokens never expire"
        else "session: ${days}d${if (sliding) " sliding" else " fixed"}"
    }
    log.info(
        "Hosting config from '${file.path}': central=$centralTarget (maxPool=$centralPool), " +
            "$sessionPolicy, " +
            (parsed.publicBaseUrl?.let { "publicBaseUrl=$it (OAuth on), " } ?: "OAuth off (no publicBaseUrl), ") +
            "${parsed.groups.size} group(s) " +
            parsed.groups.joinToString(prefix = "[", postfix = "]") { g ->
                "${g.id}(maxPool=${resolveMaxPoolSize(g.maxPoolSize, parsed.maxPoolSize, DEFAULT_GROUP_MAX_POOL)}, " +
                    "stations=${g.stations.joinToString("/") { it.id }})"
            }
    )
    return parsed
}

/**
 * host:port/database from a MySQL JDBC URL (query params stripped) - the
 * identity used to detect central/group schema collisions. Best-effort:
 * host aliases (localhost vs 127.0.0.1) are not resolved.
 */
fun databaseTarget(jdbcUrl: String): String =
    jdbcUrl.removePrefix("jdbc:mysql://").substringBefore('?').trimEnd('/')

/**
 * The hosted groups, their (lazily created) connection pools, and the
 * per-station views over them.
 *
 * A pool belongs to a GROUP (one database, shared by its stations); a
 * [StationDb] is a cheap per-station VIEW over that pool which scopes every
 * query to its `station_id`. Both are created on first access, so unused
 * groups cost nothing until someone with a grant selects one of their
 * stations.
 *
 * Koin singleton (typed definition, so inject sites are compile-checked).
 * HostingConfig is @Provided: it comes from a file-loading factory registered
 * with a classic-DSL definition the compile-time checker can't index.
 */
/**
 * One provider block inside `ai:` - an API key plus the models the client may
 * pick from (FIRST entry = that provider's default). [models] is optional for
 * anthropic (falls back to [AiChatConfig.ANTHROPIC_DEFAULT_MODELS]) and
 * REQUIRED for openai/gemini - their catalogs move too fast for a safe
 * baked-in default. A BLANK apiKey counts as unconfigured, so commenting out
 * the key and emptying it both remove the provider from the dropdown.
 */
@Serializable
data class AiProviderConfig(
    val apiKey: String = "",
    val models: List<String> = emptyList(),
)

/**
 * The in-app AI assistant (server.yaml `ai:` block) - one optional block per
 * provider. Every provider holding a key becomes an option in the client's
 * chat dropdown; [provider] optionally names the DEFAULT (preselected, listed
 * first), otherwise anthropic > openai > gemini order decides. Zero configured
 * providers = feature off: the route is unmounted and clients hide the entry.
 */
@Serializable
data class AiChatConfig(
    val provider: String? = null,
    val anthropic: AiProviderConfig? = null,
    val openai: AiProviderConfig? = null,
    val gemini: AiProviderConfig? = null,
    /** Per-response output-token cap, applied to every provider call. */
    val maxTokens: Int = 8192,
    /**
     * Schedule MUTATIONS from the chat (add/delete/reorder placements, send
     * schedule emails). Default ON: unlike the network-reachable MCP server's
     * default-deny env knob, every in-app mutation is behind the calling
     * user's staff role AND an explicit per-action confirmation card - the
     * model can only PREPARE actions, never perform them. Set false for a
     * strictly read-only assistant.
     */
    val mutations: Boolean = true,
) {
    /**
     * The providers that actually hold an API key, resolved to catalog
     * entries - DEFAULT FIRST (that order IS the client dropdown order).
     */
    fun configuredProviders(): List<AiProviderCatalogEntry> {
        val entries = buildList {
            fun addIfKeyed(id: String, block: AiProviderConfig?, fallback: List<String> = emptyList()) {
                val keyed = block?.takeIf { it.apiKey.isNotBlank() } ?: return
                val models = keyed.models.map(String::trim).filter(String::isNotEmpty).ifEmpty { fallback }
                if (models.isNotEmpty()) add(AiProviderCatalogEntry(id, models, keyed.apiKey))
            }
            addIfKeyed(PROVIDER_ANTHROPIC, anthropic, fallback = ANTHROPIC_DEFAULT_MODELS)
            addIfKeyed(PROVIDER_OPENAI, openai)
            addIfKeyed(PROVIDER_GEMINI, gemini)
        }
        val default = provider?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return entries
        // Stable sort: the default floats to the front, the rest keep their order.
        return entries.sortedBy { if (it.id == default) 0 else 1 }
    }

    companion object {
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_GEMINI = "gemini"

        /** Anthropic's stable model trio - the one catalog safe to bake in. */
        val ANTHROPIC_DEFAULT_MODELS = listOf("claude-sonnet-5", "claude-haiku-4-5", "claude-opus-4-8")
    }
}

/**
 * A configured AI provider as offered to clients: its id, the models the user
 * may choose (first = default), and the key the server calls it with. The key
 * NEVER leaves the server - client responses carry only id + models.
 */
data class AiProviderCatalogEntry(
    val id: String,
    val models: List<String>,
    val apiKey: String,
)

class StationRegistry(@Provided hosting: HostingConfig) {

    // Mutable (thread-safe) because stations and groups can be added or removed
    // LIVE: a fresh migration hosts its stations without a restart, and deleting
    // a hosted database takes effect immediately.
    private val groupConfigs = CopyOnWriteArrayList(hosting.groups)
    private val globalMaxPool: Int? = hosting.maxPoolSize

    /** File-wide SMTP default for customer emails (group/station blocks override). */
    val defaultSmtp: SmtpConfig? = hosting.smtp

    /**
     * TEST-ONLY: redirect every outgoing customer email here (see HostingConfig).
     * A BLANK value counts as unset - so commenting out OR emptying the YAML line
     * both restore normal delivery, never a send to "".
     */
    val emailRedirectTo: String? = hosting.emailRedirectTo?.takeIf { it.isNotBlank() }

    /** Public host(s) for the MCP SSE endpoint; null keeps the SDK's localhost defaults. */
    val mcpAllowedHosts: List<String>? =
        hosting.mcpAllowedHosts?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    /** Serve the OpenAPI/Swagger UI at /swagger (server.yaml `swagger: true`). */
    val swaggerEnabled: Boolean = hosting.swagger

    /**
     * The OAuth issuer / public origin (server.yaml `publicBaseUrl`), trailing
     * slash trimmed. Null = OAuth endpoints are not mounted (PATs still work).
     */
    val publicBaseUrl: String? = hosting.publicBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    /** Install XForwardedHeaders (server.yaml `behindReverseProxy: true`). */
    val behindReverseProxy: Boolean = hosting.behindReverseProxy

    /**
     * In-app AI assistant: the providers holding an API key, DEFAULT FIRST
     * (server.yaml `ai:`). Empty = feature off - the chat route stays
     * unmounted and clients hide the entry entirely.
     */
    val aiChatProviders: List<AiProviderCatalogEntry> = hosting.ai?.configuredProviders().orEmpty()

    /** Per-response output-token cap for AI chat provider calls. */
    val aiChatMaxTokens: Int = hosting.ai?.maxTokens ?: 8192

    /** Chat mutations (confirmation-card gated); server.yaml `ai.mutations`, default true. */
    val aiChatMutations: Boolean = hosting.ai?.mutations ?: true

    /** One pool + one schema per GROUP. */
    private val pools = ConcurrentHashMap<String, GroupDb>()

    /** One view per STATION, over its group's pool. */
    private val views = ConcurrentHashMap<String, StationDb>()

    val groups: List<GroupConfig> get() = groupConfigs.toList()

    /** Every hosted station, flattened - their ids are globally unique. */
    val all: List<StationConfig> get() = groupConfigs.flatMap { it.stations }

    val ids: List<String> get() = all.map { it.id }

    fun config(id: String): StationConfig? = all.firstOrNull { it.id == id }

    fun groupConfig(groupId: String): GroupConfig? = groupConfigs.firstOrNull { it.id == groupId }

    /** The group hosting [stationId], or null when it is not hosted. */
    fun group(stationId: String): GroupConfig? =
        groupConfigs.firstOrNull { g -> g.stations.any { it.id == stationId } }

    /** SMTP for a station: its own block, else its group's, else the file-wide default. */
    fun smtpFor(stationId: String): SmtpConfig? =
        config(stationId)?.smtp ?: group(stationId)?.smtp ?: defaultSmtp

    /**
     * The station's DB view (its group's pool + schema are created and
     * bootstrapped on first call).
     */
    fun db(id: String): StationDb? {
        val station = config(id) ?: return null
        val group = group(id) ?: return null
        return views.computeIfAbsent(id) { StationDb(groupDb(group), station).also { it.bootstrap() } }
    }

    /** The group's pool, created + schema-bootstrapped on first call. */
    private fun groupDb(group: GroupConfig): GroupDb =
        pools.computeIfAbsent(group.id) {
            val maxPool = resolveMaxPoolSize(group.maxPoolSize, globalMaxPool, DEFAULT_GROUP_MAX_POOL)
            GroupDb(group, maxPool).also { it.bootstrap() }
        }

    /**
     * Hosts a whole group NOW (a fresh migration): API calls work immediately,
     * no restart. The caller must have persisted it to server.yaml too, or it
     * disappears at the next boot.
     */
    fun addGroup(config: GroupConfig) {
        require(groupConfigs.none { it.id == config.id }) { "Group '${config.id}' is already hosted" }
        val clashing = config.stations.map { it.id }.filter { it in ids }
        require(clashing.isEmpty()) { "Station id(s) $clashing are already hosted in another group" }
        groupConfigs.add(config)
    }

    /** Hosts one more station inside an already-hosted group. */
    fun addStation(groupId: String, station: StationConfig) {
        val group = requireNotNull(groupConfig(groupId)) { "Unknown group '$groupId'" }
        require(config(station.id) == null) { "Station '${station.id}' is already hosted" }
        groupConfigs[groupConfigs.indexOf(group)] = group.copy(stations = group.stations + station)
    }

    /**
     * Unhosts a station NOW: every subsequent API call for it 404s ("unknown
     * station"), including the super admin's implicit grants, which are
     * synthesized from [ids] per request. Returns the removed config.
     *
     * The group's POOL survives - its siblings still need it. It closes only
     * when the last station of the group goes (see [removeGroup]).
     */
    fun removeStation(id: String): StationConfig? {
        val group = group(id) ?: return null
        val station = group.stations.first { it.id == id }
        groupConfigs[groupConfigs.indexOf(group)] = group.copy(stations = group.stations - station)
        views.remove(id)
        return station
    }

    /**
     * Unhosts a whole group and closes its pool. Returns the removed config
     * (the caller needs its credentials for a hard delete: the database is the
     * GROUP's, so dropping it takes every station in it).
     */
    fun removeGroup(groupId: String): GroupConfig? {
        val group = groupConfig(groupId) ?: return null
        groupConfigs.remove(group)
        group.stations.forEach { views.remove(it.id) }
        pools.remove(groupId)?.close()
        return group
    }

    fun closeAll() {
        views.clear()
        pools.values.forEach { it.close() }
        pools.clear()
    }
}
