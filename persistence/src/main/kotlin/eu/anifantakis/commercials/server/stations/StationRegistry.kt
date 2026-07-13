package eu.anifantakis.commercials.server.stations

import com.charleskorn.kaml.Yaml
import eu.anifantakis.commercials.server.scheduler.StationDb
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Provided
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Built-in connection-pool ceilings when nothing is set in server.yaml. */
const val DEFAULT_CENTRAL_MAX_POOL = 10
const val DEFAULT_STATION_MAX_POOL = 5

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
 * One entry per hosted station (TV or radio). Identical table structure per
 * schema; the schemas may live on the same MySQL server or different ones.
 *
 * @param maxPoolSize optional per-station HikariCP pool ceiling override.
 */
@Serializable
data class StationConfig(
    /** Stable key used in grants and API calls, e.g. "crete-tv". */
    val id: String,
    /** Display name shown to users, e.g. "Crete TV". */
    val name: String,
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maxPoolSize: Int? = null,
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
    /** Overrides the file-wide smtp block for this station's customer emails. */
    val smtp: SmtpConfig? = null,
)

/**
 * SMTP settings for customer schedule emails (≙ legacy `emailsetup`).
 * Optional: without one, email endpoints answer with a clear 400. A station
 * block overrides the file-wide default (each station traditionally sent
 * from its own address, e.g. dmaria@cretetv.gr).
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
 *   It must never double as a station schema.
 * - [superAdmin] is MANDATORY: the config-managed administrator (see
 *   [SuperAdminConfig]). Validated non-null at load; use [admin].
 * - [stations] is 0..n: a server may host no stations at all (users can log
 *   in but have nothing to select) or many.
 * - [maxPoolSize] is the file-wide default HikariCP pool ceiling applied to
 *   central and every station unless they override it. When null, the
 *   built-in defaults apply (central 10, station 5). Tune this against the
 *   MySQL server's `max_connections` as the number of stations grows.
 */
@Serializable
data class HostingConfig(
    val central: DbConnectionConfig,
    val superAdmin: SuperAdminConfig? = null,
    val stations: List<StationConfig> = emptyList(),
    val maxPoolSize: Int? = null,
    /** File-wide SMTP default for customer emails (stations may override). */
    val smtp: SmtpConfig? = null,
    /** Bearer-token lifetime policy. Defaults to expiration on / 90 days / sliding. */
    val session: SessionConfig = SessionConfig(),
) {
    /** The super admin, guaranteed by [loadHostingConfig]'s validation. */
    val admin: SuperAdminConfig get() = requireNotNull(superAdmin) { "superAdmin missing - config not loaded via loadHostingConfig?" }
}

/**
 * Loads the hosting layout from server.yaml (path via `server.config`
 * system property or `COMMERCIALS_SERVER` env, default `./server.yaml`).
 *
 * The file - and its `central` block - are REQUIRED: the server cannot run
 * without its own schema. Station entries are optional.
 */
fun loadHostingConfig(): HostingConfig {
    val log = LoggerFactory.getLogger("StationRegistry")
    val explicit = System.getProperty("server.config") ?: System.getenv("COMMERCIALS_SERVER")
    val file = File(explicit ?: "server.yaml")

    require(file.exists()) {
        "Required config '${file.path}' not found. It must define the mandatory 'central' database " +
            "(users/tokens/grants) and optionally 0..n hosted stations. " +
            "Override the path with -Dserver.config=<path> or COMMERCIALS_SERVER."
    }

    val parsed = Yaml.default.decodeFromString(HostingConfig.serializer(), file.readText())

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

    val duplicates = parsed.stations.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) { "Duplicate station ids in '${file.path}': $duplicates" }

    // Any specified pool ceiling must be positive
    val declaredPoolSizes = buildList {
        add("(global) maxPoolSize" to parsed.maxPoolSize)
        add("central.maxPoolSize" to parsed.central.maxPoolSize)
        parsed.stations.forEach { add("station '${it.id}' maxPoolSize" to it.maxPoolSize) }
    }
    declaredPoolSizes.forEach { (who, size) ->
        require(size == null || size > 0) { "$who must be > 0 in '${file.path}' (was $size)" }
    }

    // The central schema is standalone by contract - reject configs that
    // point a station at the same database as central.
    val centralTarget = databaseTarget(parsed.central.jdbcUrl)
    val clashing = parsed.stations.filter { databaseTarget(it.jdbcUrl) == centralTarget }
    require(clashing.isEmpty()) {
        "Station(s) ${clashing.map { it.id }} use the CENTRAL database ($centralTarget) as their schema. " +
            "The central schema is standalone and must not double as a station schema."
    }

    val centralPool = resolveMaxPoolSize(parsed.central.maxPoolSize, parsed.maxPoolSize, DEFAULT_CENTRAL_MAX_POOL)
    val sessionPolicy = with(parsed.session) {
        if (!expiration) "session: tokens never expire"
        else "session: ${days}d${if (sliding) " sliding" else " fixed"}"
    }
    log.info(
        "Hosting config from '${file.path}': central=$centralTarget (maxPool=$centralPool), " +
            "$sessionPolicy, ${parsed.stations.size} station(s) " +
            parsed.stations.joinToString(prefix = "[", postfix = "]") {
                "${it.id}(maxPool=${resolveMaxPoolSize(it.maxPoolSize, parsed.maxPoolSize, DEFAULT_STATION_MAX_POOL)})"
            }
    )
    return parsed
}

/**
 * host:port/database from a MySQL JDBC URL (query params stripped) - the
 * identity used to detect central/station schema collisions. Best-effort:
 * host aliases (localhost vs 127.0.0.1) are not resolved.
 */
fun databaseTarget(jdbcUrl: String): String =
    jdbcUrl.removePrefix("jdbc:mysql://").substringBefore('?').trimEnd('/')

/**
 * The hosted stations and their (lazily created) connection pools. A station's
 * pool + schema bootstrap happen on first access, so unused stations cost
 * nothing until someone with a grant selects them.
 *
 * Koin singleton (typed definition, so inject sites are compile-checked).
 * HostingConfig is @Provided: it comes from a file-loading factory registered
 * with a classic-DSL definition the compile-time checker can't index.
 */
class StationRegistry(@Provided hosting: HostingConfig) {

    // Mutable (thread-safe) because stations can be REMOVED live: deleting a
    // hosted database takes effect immediately, without a server restart
    // (unlike additions, which come from server.yaml at boot).
    private val configs = java.util.concurrent.CopyOnWriteArrayList(hosting.stations)
    private val globalMaxPool: Int? = hosting.maxPoolSize

    /** File-wide SMTP default for customer emails (station blocks override). */
    val defaultSmtp: SmtpConfig? = hosting.smtp

    private val pools = ConcurrentHashMap<String, StationDb>()

    val all: List<StationConfig> get() = configs.toList()

    val ids: List<String> get() = configs.map { it.id }

    fun config(id: String): StationConfig? = configs.firstOrNull { it.id == id }

    /** The station's DB (pool created + schema bootstrapped on first call). */
    fun db(id: String): StationDb? {
        val config = config(id) ?: return null
        return pools.computeIfAbsent(id) {
            val maxPool = resolveMaxPoolSize(config.maxPoolSize, globalMaxPool, DEFAULT_STATION_MAX_POOL)
            StationDb(config, maxPool).also { it.bootstrap() }
        }
    }

    /**
     * Hosts a station NOW (a fresh migration): API calls work immediately,
     * no restart. The caller must have persisted it to server.yaml too,
     * or it disappears at the next boot.
     */
    fun add(config: StationConfig) {
        require(configs.none { it.id == config.id }) { "Station '${config.id}' is already hosted" }
        configs.add(config)
    }

    /**
     * Unhosts a station NOW: its pool closes and every subsequent API call
     * for it 404s ("unknown station"), including the super admin's implicit
     * grants, which are synthesized from [ids] per request. Returns the
     * removed config (the caller needs its credentials for a hard delete).
     */
    fun remove(id: String): StationConfig? {
        val config = config(id) ?: return null
        configs.removeIf { it.id == id }
        pools.remove(id)?.close()
        return config
    }

    fun closeAll() {
        pools.values.forEach { it.close() }
        pools.clear()
    }
}
