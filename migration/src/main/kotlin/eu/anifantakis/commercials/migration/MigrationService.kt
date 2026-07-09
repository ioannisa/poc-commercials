package eu.anifantakis.commercials.migration

import eu.anifantakis.commercials.server.scheduler.StationDb
import eu.anifantakis.commercials.server.stations.StationConfig
import eu.anifantakis.commercials.server.stations.StationRegistry
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Creates the normalized station tables in [jdbcUrl]'s schema WITHOUT demo
 * seeding (station_meta.demo_seed=false, so empty months stay empty forever).
 * The DDL stays single-sourced in :persistence's [StationDb]. Idempotent.
 */
internal fun prepareStationSchema(jdbcUrl: String, username: String, password: String) {
    val schema = jdbcUrl.substringAfterLast('/').substringBefore('?')
    val db = StationDb(
        StationConfig(id = schema, name = schema, jdbcUrl = jdbcUrl, username = username, password = password),
        maxPoolSize = 2
    )
    try {
        db.bootstrap(seedDemo = false)
    } finally {
        db.close()
    }
}

/**
 * The engine behind the in-app Migration screen: one legacy-dump migration
 * at a time, driven by the super admin over the API and executed here on the
 * server (which is where the dump files, the MySQL credentials and
 * server.yaml live - the browser only steers).
 *
 * State machine:
 *
 *   IDLE ──start()──▶ REPLAYING ──▶ AWAITING_FLOW ──chooseFlow()──▶ TRANSFORMING ──▶ DONE
 *                        │                                              │
 *                        └──────────────────▶ FAILED ◀─────────────────┘   (reset() ▶ IDLE)
 *
 * The pause at AWAITING_FLOW exists because a legacy DB can hold BOTH a TV
 * and a radio flow - the operator sees the real per-flow counts before
 * deciding which one lands in the target schema.
 *
 * Koin singleton; all state transitions are synchronized, the heavy work
 * runs on a background thread appending to [logLines] for live polling.
 */
class MigrationService(private val registry: StationRegistry) {

    enum class State { IDLE, REPLAYING, AWAITING_FLOW, TRANSFORMING, DONE, FAILED }

    data class FlowInfo(val forTv: Int, val spots: Long, val placements: Long)

    data class Snapshot(
        val state: State,
        val log: List<String>,
        val flows: List<FlowInfo>,
        val summary: LegacyTransformer.Summary?,
        val error: String?,
        val schema: String?,
    )

    data class StartRequest(
        val dumpPath: String,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val schema: String,
        val createSchema: Boolean,
        /**
         * Optional folder of SEN (Oracle ERP) table exports on the SERVER
         * (one tab-delimited file per Oracle table). When present, the
         * transform is followed by the ERP enrichment: real customer
         * names/VAT/contacts, real contract periods, corrected gift flags
         * (see SenErpEnricher).
         */
        val senDirPath: String? = null,
    )

    data class FlowRequest(
        val forTv: Int,
        val stationId: String,
        val stationName: String,
        val addToYaml: Boolean,
    )

    @Volatile private var state = State.IDLE
    private val logLines = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var flows: List<FlowInfo> = emptyList()
    @Volatile private var summary: LegacyTransformer.Summary? = null
    @Volatile private var error: String? = null
    @Volatile private var request: StartRequest? = null

    private val schemaNamePattern = Regex("[a-zA-Z0-9_]{1,64}")
    private val stationIdPattern = Regex("[a-z0-9][a-z0-9-]{1,63}")

    fun snapshot() = Snapshot(state, logLines.toList(), flows, summary, error, request?.schema)

    @Synchronized
    fun reset() {
        check(state in setOf(State.DONE, State.FAILED, State.IDLE)) { "A migration is still running" }
        state = State.IDLE
        logLines.clear(); flows = emptyList(); summary = null; error = null; request = null
    }

    /**
     * Validates everything fast (dump, schema, empty target) on the caller's
     * thread so mistakes surface as immediate 400s, then replays the dump in
     * the background until the flow decision point.
     */
    @Synchronized
    fun start(req: StartRequest) {
        check(state == State.IDLE) { "A migration is already ${state.name.lowercase()} - reset it first" }
        val dump = File(req.dumpPath)
        require(dump.isFile) { "Dump file not found on the server: ${req.dumpPath}" }
        require(schemaNamePattern.matches(req.schema)) { "Schema name must match ${schemaNamePattern.pattern}" }
        req.senDirPath?.let {
            require(File(it).isDirectory) { "SEN export folder not found on the server: $it" }
        }

        connect(req).use { c ->
            val exists = schemaExists(c, req.schema)
            when {
                !exists && req.createSchema ->
                    c.createStatement().use { it.executeUpdate("CREATE DATABASE `${req.schema}` DEFAULT CHARACTER SET utf8mb4") }
                !exists -> throw IllegalArgumentException("Schema '${req.schema}' does not exist - tick 'create schema'")
                else -> Unit
            }

            prepareStationSchema(targetJdbcUrl(req), req.username, req.password)

            val placements = c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM `${req.schema}`.placements").use { rs -> rs.next(); rs.getLong(1) }
            }
            require(placements == 0L) {
                "Schema '${req.schema}' already holds $placements placements - migrate into a fresh schema"
            }
        }

        request = req
        state = State.REPLAYING
        logLines.clear(); flows = emptyList(); summary = null; error = null
        log("Created/verified schema '${req.schema}' - normalized tables ready (demo seeding disabled).")
        log("Replaying '${dump.name}' (${dump.length() / 1_048_576} MB) - includes the email archive; irrelevant tables are skipped...")

        thread(name = "migration-replay") {
            try {
                connect(req).use { c ->
                    val scratch = scratchSchema(req)
                    c.createStatement().use {
                        it.executeUpdate("DROP DATABASE IF EXISTS `$scratch`")
                        it.executeUpdate("CREATE DATABASE `$scratch` DEFAULT CHARACTER SET utf8mb4")
                    }
                    val replayed = DumpReplayer(c, scratch, ::log).replay(dump)
                    replayed.forEach { (table, rows) -> log("  %-28s %,d rows".format(table, rows)) }

                    flows = c.createStatement().use { st ->
                        st.executeQuery(
                            """
                            SELECT m.forTV, COUNT(DISTINCT m.id), COUNT(sch.id)
                            FROM `$scratch`.messages m
                            LEFT JOIN `$scratch`.schedule sch ON sch.messageID = m.id
                            GROUP BY m.forTV ORDER BY m.forTV
                            """.trimIndent()
                        ).use { rs ->
                            buildList { while (rs.next()) add(FlowInfo(rs.getInt(1), rs.getLong(2), rs.getLong(3))) }
                        }
                    }
                }
                log("Replay complete. Choose which flow to migrate.")
                state = State.AWAITING_FLOW
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Continues from AWAITING_FLOW: transform, cleanup, optional yaml entry. */
    @Synchronized
    fun chooseFlow(flowReq: FlowRequest) {
        check(state == State.AWAITING_FLOW) { "Not awaiting a flow choice (state: $state)" }
        val req = requireNotNull(request)
        require(flows.any { it.forTv == flowReq.forTv }) { "No forTV=${flowReq.forTv} flow in this dump" }
        if (flowReq.addToYaml) {
            require(stationIdPattern.matches(flowReq.stationId)) { "Station id must match ${stationIdPattern.pattern}" }
            require(flowReq.stationName.isNotBlank()) { "Station name must not be blank" }
            require(registry.config(flowReq.stationId) == null) { "Station id '${flowReq.stationId}' is already hosted" }
        }

        state = State.TRANSFORMING
        log("Transforming forTV=${flowReq.forTv} into '${req.schema}'...")

        thread(name = "migration-transform") {
            try {
                connect(req).use { c ->
                    val scratch = scratchSchema(req)
                    summary = LegacyTransformer(c, scratch, req.schema, flowReq.forTv) { log("  $it") }.run()
                    req.senDirPath?.let { senDir ->
                        log("Enriching from the SEN (Oracle ERP) exports in $senDir ...")
                        SenErpEnricher(c, req.schema) { log("  $it") }
                            .enrich(File(senDir), apply = true, legacyScratchSchema = scratch)
                    } ?: log("(no SEN folder given - the ERP enrichment can run later from the CLI)")
                    c.createStatement().use { it.executeUpdate("DROP DATABASE `$scratch`") }
                    log("Scratch schema dropped.")
                }
                if (flowReq.addToYaml) {
                    appendStationToYaml(
                        file = stationsYamlFile(),
                        id = flowReq.stationId,
                        name = flowReq.stationName,
                        jdbcUrl = targetJdbcUrl(req),
                        username = req.username,
                        password = req.password,
                    )
                    // Hosted LIVE - no restart needed; the yaml entry above
                    // makes it survive the next boot.
                    registry.add(
                        StationConfig(
                            id = flowReq.stationId,
                            name = flowReq.stationName,
                            jdbcUrl = targetJdbcUrl(req),
                            username = req.username,
                            password = req.password,
                        )
                    )
                    log("Station '${flowReq.stationId}' added to ${stationsYamlFile().path} and hosted LIVE - no restart needed.")
                    log("It appears in user dropdowns at their next login; the super admin can grant access right away.")
                }
                log("Migration finished.")
                state = State.DONE
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun fail(e: Exception) {
        error = e.message ?: e.toString()
        log("FAILED: $error")
        state = State.FAILED
    }

    private fun log(line: String) {
        logLines += line
    }

    private fun connect(req: StartRequest): Connection = DriverManager.getConnection(
        "jdbc:mysql://${req.host}:${req.port}/?useUnicode=true&characterEncoding=utf8" +
            "&zeroDateTimeBehavior=convertToNull&allowPublicKeyRetrieval=true&useSSL=false",
        req.username, req.password
    )

    private fun targetJdbcUrl(req: StartRequest) =
        "jdbc:mysql://${req.host}:${req.port}/${req.schema}?useUnicode=true&characterEncoding=utf8"

    private fun scratchSchema(req: StartRequest) = "${req.schema}_scratch"

    private fun schemaExists(c: Connection, schema: String): Boolean =
        c.prepareStatement("SELECT 1 FROM information_schema.schemata WHERE schema_name = ?").use { ps ->
            ps.setString(1, schema)
            ps.executeQuery().use { it.next() }
        }

}
