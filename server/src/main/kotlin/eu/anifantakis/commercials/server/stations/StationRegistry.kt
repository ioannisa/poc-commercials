package eu.anifantakis.commercials.server.stations

import com.charleskorn.kaml.Yaml
import eu.anifantakis.commercials.server.scheduler.StationDb
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Provided
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Built-in connection-pool ceilings when nothing is set in stations.yaml. */
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
 * design: stations.yaml is server-side deployment config).
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
)

/**
 * The break-glass administrator account, deliberately PLAINTEXT in
 * stations.yaml (same threat model as the DB credentials around it: whoever
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
) {
    /** The super admin, guaranteed by [loadHostingConfig]'s validation. */
    val admin: SuperAdminConfig get() = requireNotNull(superAdmin) { "superAdmin missing - config not loaded via loadHostingConfig?" }
}

/**
 * Loads the hosting layout from stations.yaml (path via `stations.config`
 * system property or `POC_STATIONS` env, default `./stations.yaml`).
 *
 * The file - and its `central` block - are REQUIRED: the server cannot run
 * without its own schema. Station entries are optional.
 */
fun loadHostingConfig(): HostingConfig {
    val log = LoggerFactory.getLogger("StationRegistry")
    val explicit = System.getProperty("stations.config") ?: System.getenv("POC_STATIONS")
    val file = File(explicit ?: "stations.yaml")

    require(file.exists()) {
        "Required config '${file.path}' not found. It must define the mandatory 'central' database " +
            "(users/tokens/grants) and optionally 0..n hosted stations. " +
            "Override the path with -Dstations.config=<path> or POC_STATIONS."
    }

    val parsed = Yaml.default.decodeFromString(HostingConfig.serializer(), file.readText())

    requireNotNull(parsed.superAdmin) {
        "'${file.path}' must define the mandatory 'superAdmin' block (username + password) - " +
            "the config-managed administrator account that creates users and resets passwords."
    }
    require(parsed.superAdmin.username.isNotBlank() && parsed.superAdmin.password.isNotBlank()) {
        "superAdmin.username and superAdmin.password must be non-blank in '${file.path}'"
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
    log.info(
        "Hosting config from '${file.path}': central=$centralTarget (maxPool=$centralPool), " +
            "${parsed.stations.size} station(s) " +
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
internal fun databaseTarget(jdbcUrl: String): String =
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

    private val configs: List<StationConfig> = hosting.stations
    private val globalMaxPool: Int? = hosting.maxPoolSize

    private val pools = ConcurrentHashMap<String, StationDb>()

    val all: List<StationConfig> get() = configs

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

    fun closeAll() {
        pools.values.forEach { it.close() }
        pools.clear()
    }
}
