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
            "$sessionPolicy, ${parsed.groups.size} group(s) " +
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
class StationRegistry(@Provided hosting: HostingConfig) {

    // Mutable (thread-safe) because stations and groups can be added or removed
    // LIVE: a fresh migration hosts its stations without a restart, and deleting
    // a hosted database takes effect immediately.
    private val groupConfigs = CopyOnWriteArrayList(hosting.groups)
    private val globalMaxPool: Int? = hosting.maxPoolSize

    /** File-wide SMTP default for customer emails (group/station blocks override). */
    val defaultSmtp: SmtpConfig? = hosting.smtp

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
