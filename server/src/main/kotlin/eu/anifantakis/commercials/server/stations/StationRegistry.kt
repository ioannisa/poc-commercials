package eu.anifantakis.commercials.server.stations

import com.charleskorn.kaml.Yaml
import eu.anifantakis.commercials.server.scheduler.StationDb
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Provided
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A MySQL connection target (full JDBC URL + credentials, plaintext by
 * design: stations.yaml is server-side deployment config).
 */
@Serializable
data class DbConnectionConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
)

/**
 * One entry per hosted station (TV or radio). Identical table structure per
 * schema; the schemas may live on the same MySQL server or different ones.
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
)

/**
 * The whole hosting layout:
 * - [central] is MANDATORY and STANDALONE: the server's own schema
 *   (users, tokens, per-station grants - e.g. `commercials_central`).
 *   It must never double as a station schema.
 * - [stations] is 0..n: a server may host no stations at all (users can log
 *   in but have nothing to select) or many.
 */
@Serializable
data class HostingConfig(
    val central: DbConnectionConfig,
    val stations: List<StationConfig> = emptyList(),
)

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

    val duplicates = parsed.stations.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) { "Duplicate station ids in '${file.path}': $duplicates" }

    // The central schema is standalone by contract - reject configs that
    // point a station at the same database as central.
    val centralTarget = databaseTarget(parsed.central.jdbcUrl)
    val clashing = parsed.stations.filter { databaseTarget(it.jdbcUrl) == centralTarget }
    require(clashing.isEmpty()) {
        "Station(s) ${clashing.map { it.id }} use the CENTRAL database ($centralTarget) as their schema. " +
            "The central schema is standalone and must not double as a station schema."
    }

    log.info(
        "Hosting config from '${file.path}': central=$centralTarget, " +
            "${parsed.stations.size} station(s) ${parsed.stations.map { it.id }}"
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

    private val pools = ConcurrentHashMap<String, StationDb>()

    val all: List<StationConfig> get() = configs

    val ids: List<String> get() = configs.map { it.id }

    fun config(id: String): StationConfig? = configs.firstOrNull { it.id == id }

    /** The station's DB (pool created + schema bootstrapped on first call). */
    fun db(id: String): StationDb? {
        val config = config(id) ?: return null
        return pools.computeIfAbsent(id) { StationDb(config).also { it.bootstrap() } }
    }

    fun closeAll() {
        pools.values.forEach { it.close() }
        pools.clear()
    }
}
