package eu.anifantakis.commercials.migration

import eu.anifantakis.commercials.server.scheduler.GroupDb
import eu.anifantakis.commercials.server.scheduler.StationDb
import eu.anifantakis.commercials.server.stations.GroupConfig
import eu.anifantakis.commercials.server.stations.StationConfig
import eu.anifantakis.commercials.server.stations.StationRegistry
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Creates the normalized GROUP tables in [jdbcUrl]'s schema and registers
 * [stations] in them, WITHOUT demo seeding (station_meta.demo_seed=false per
 * station, so empty months stay empty forever). The DDL stays single-sourced in
 * :persistence's [GroupDb]. Idempotent.
 *
 * The station rows must exist before the transform runs: spots, programs and
 * breaks all carry a `station_id` foreign key into them.
 */
internal fun prepareGroupSchema(
    groupId: String,
    jdbcUrl: String,
    username: String,
    password: String,
    stations: List<StationConfig> = emptyList(),
) {
    val group = GroupDb(
        GroupConfig(
            id = groupId,
            jdbcUrl = jdbcUrl,
            username = username,
            password = password,
            stations = stations,
        ),
        maxPoolSize = 2
    )
    try {
        group.bootstrap()
        stations.forEach { StationDb(group, it).bootstrap(seedDemo = false) }
    } finally {
        group.close()
    }
}

/**
 * The engine behind the in-app Migration screen: one legacy-dump migration at a
 * time, driven by the super admin over the API and executed here on the server
 * (which is where the dump files, the MySQL credentials and server.yaml live -
 * the browser only steers).
 *
 * State machine:
 *
 *   IDLE ──start()──▶ REPLAYING ──▶ AWAITING_FLOW ──chooseMapping()──▶ TRANSFORMING ──▶ DONE
 *                        │                                                 │
 *                        └──────────────────▶ FAILED ◀────────────────────┘   (reset() ▶ IDLE)
 *
 * The pause at AWAITING_FLOW is where the group model shows up. A legacy DB
 * holds BOTH a TV and a radio flow, and they are ONE COMPANY sharing customers
 * and contracts - so the operator does not choose one flow and throw the other
 * away (that was the old model, and it duplicated every customer). He sees the
 * real per-flow counts and MAPS EACH FLOW TO A STATION of the same group. One
 * dump ⇒ one group database ⇒ 1..n stations, in a single run.
 *
 * Koin singleton; all state transitions are synchronized, the heavy work runs on
 * a background thread appending to [logLines] for live polling.
 */
class MigrationService(private val registry: StationRegistry) {

    enum class State { IDLE, REPLAYING, AWAITING_FLOW, TRANSFORMING, DONE, FAILED }

    data class FlowInfo(val forTv: Int, val spots: Long, val placements: Long)

    data class Snapshot(
        val state: State,
        val log: List<String>,
        /** Null until something measurable is running (see [Progress]). */
        val progress: Progress?,
        val flows: List<FlowInfo>,
        val summary: LegacyTransformer.Summary?,
        val error: String?,
        val schema: String?,
        /** The hosted groups a migration may target (id to display name). */
        val groups: List<GroupOption>,
    )

    /** A group the operator can migrate into, as offered by the wizard. */
    data class GroupOption(val id: String, val name: String, val schema: String)

    /**
     * Where the migration is, in numbers the operator can trust.
     *
     * NOT invented: [done]/[total] is megabytes of the dump during the replay (the
     * only phase with a real measurable size, and the longest), and steps-completed
     * during the transform and the enrichment. When a phase can offer no honest
     * number, [total] is 0 and the bar must render INDETERMINATE rather than guess.
     */
    data class Progress(
        /** REPLAY | TRANSFORM | ENRICH - which bar, and what the unit means. */
        val phase: String,
        /** The step or file being worked on, for the caption under the bar. */
        val label: String,
        val done: Long,
        /** 0 = no honest total; show an indeterminate bar. */
        val total: Long,
        /**
         * WITHIN-step progress, for the steps big enough to measure their
         * inside (the verbatim copies, the placements bulk load, the
         * break-entity build). Same honesty rule: [subTotal] 0 = the running
         * step reports none, hide the sub-bar.
         */
        val subDone: Long = 0,
        val subTotal: Long = 0,
    )

    data class StartRequest(
        val dumpPath: String,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        /** Target group: an already-hosted one, or a new id to create. */
        val groupId: String,
        /** Display name - only used when the group is new. */
        val groupName: String? = null,
        /** Target schema - only used when the group is new (an existing one owns its own). */
        val schema: String,
        val createSchema: Boolean,
        /**
         * Optional folder of SEN (Oracle ERP) table exports on the SERVER
         * (one tab-delimited file per Oracle table). When present, the
         * transform is followed by the ERP enrichment: real customer
         * names/VAT/contacts, real contract periods, corrected gift flags
         * (see SenErpEnricher). It runs ONCE per group - customers, contracts
         * and the item catalog are group-scoped.
         */
        val senDirPath: String? = null,
    )

    /** One legacy flow (forTV 0/1) and the station of the group it becomes. */
    data class FlowMapping(
        val forTv: Int,
        val stationId: String,
        val stationName: String,
        val logo: String? = null,
    )

    data class MappingRequest(
        /** One entry per flow the operator wants. A flow left out is not migrated. */
        val mappings: List<FlowMapping>,
        val addToYaml: Boolean,
    )

    private val migrationLog = LoggerFactory.getLogger("LegacyMigration")

    @Volatile private var state = State.IDLE
    private val logLines = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var flows: List<FlowInfo> = emptyList()
    @Volatile private var summary: LegacyTransformer.Summary? = null
    @Volatile private var error: String? = null
    @Volatile private var progress: Progress? = null
    @Volatile private var request: StartRequest? = null
    /** Set at start(): the group is either already hosted or brand new. */
    @Volatile private var existingGroup: GroupConfig? = null

    private val schemaNamePattern = Regex("[a-zA-Z0-9_]{1,64}")
    private val idPattern = Regex("[a-z0-9][a-z0-9-]{1,63}")

    fun snapshot() = Snapshot(
        state = state,
        log = logLines.toList(),
        progress = progress,
        flows = flows,
        summary = summary,
        error = error,
        schema = request?.let { targetSchema(it) },
        groups = registry.groups.map {
            GroupOption(it.id, it.name ?: it.id, it.jdbcUrl.substringAfterLast('/').substringBefore('?'))
        },
    )

    @Synchronized
    fun reset() {
        check(state in setOf(State.DONE, State.FAILED, State.IDLE)) { "A migration is still running" }
        state = State.IDLE
        logLines.clear(); flows = emptyList(); summary = null; error = null
        request = null; existingGroup = null; progress = null
    }

    /**
     * Validates everything fast (dump, group, schema, empty target) on the
     * caller's thread so mistakes surface as immediate 400s, then replays the
     * dump in the background until the flow-mapping decision point.
     */
    @Synchronized
    fun start(req: StartRequest) {
        check(state == State.IDLE) { "A migration is already ${state.name.lowercase()} - reset it first" }
        val dump = File(req.dumpPath)
        require(dump.isFile) { "Dump file not found on the server: ${req.dumpPath}" }
        require(idPattern.matches(req.groupId)) { "Group id must match ${idPattern.pattern}" }
        req.senDirPath?.let {
            require(File(it).isDirectory) { "SEN export folder not found on the server: $it" }
        }

        val hosted = registry.groupConfig(req.groupId)
        existingGroup = hosted
        if (hosted == null) {
            require(!req.groupName.isNullOrBlank()) { "A new group needs a display name" }
            require(schemaNamePattern.matches(req.schema)) { "Schema name must match ${schemaNamePattern.pattern}" }
        }
        val schema = targetSchema(req)

        connect(req).use { c ->
            val exists = schemaExists(c, schema)
            when {
                !exists && req.createSchema ->
                    c.createStatement().use { it.executeUpdate("CREATE DATABASE `$schema` DEFAULT CHARACTER SET utf8mb4") }
                !exists -> throw IllegalArgumentException("Schema '$schema' does not exist - tick 'create schema'")
                else -> Unit
            }

            prepareGroupSchema(req.groupId, targetJdbcUrl(req), targetUsername(req), targetPassword(req))

            // CUSTOMERS, not placements: customers and contracts are GROUP-scoped
            // and the transformer inserts them unconditionally, so migrating a
            // second dump into a populated group would duplicate every one of
            // them - the exact bug the group model exists to kill. (The unique
            // key on legacy_id would abort it mid-way; this fails first, and says
            // why.)
            val customers = c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM `$schema`.customers").use { rs -> rs.next(); rs.getLong(1) }
            }
            require(customers == 0L) {
                "Group schema '$schema' already holds $customers customers. A migration imports the dump's " +
                    "customers and contracts for the WHOLE group, so it must run into an EMPTY group schema. " +
                    "(Both flows of one dump go in together - there is no second run to add.)"
            }
        }

        request = req
        state = State.REPLAYING
        logLines.clear(); flows = emptyList(); summary = null; error = null
        log("Created/verified group schema '$schema' - normalized tables ready (demo seeding disabled).")
        log("Replaying '${dump.name}' (${dump.length() / 1_048_576} MB) - includes the email archive; irrelevant tables are skipped...")

        thread(name = "migration-replay") {
            try {
                connect(req).use { c ->
                    val scratch = scratchSchema(req)
                    c.createStatement().use {
                        it.executeUpdate("DROP DATABASE IF EXISTS `$scratch`")
                        it.executeUpdate("CREATE DATABASE `$scratch` DEFAULT CHARACTER SET utf8mb4")
                    }
                    val replayed = DumpReplayer(
                        c, scratch, ::log,
                        onProgress = { mb, totalMb ->
                            progress = Progress("REPLAY", dump.name, mb, totalMb)
                        },
                    ).replay(dump)
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
                log("Replay complete. Map each flow to a station of the group - they will share its customers and contracts.")
                state = State.AWAITING_FLOW
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /**
     * Continues from AWAITING_FLOW: create the mapped stations, transform BOTH
     * flows in one pass, enrich once, clean up, optionally write server.yaml.
     */
    @Synchronized
    fun chooseMapping(mapReq: MappingRequest) {
        check(state == State.AWAITING_FLOW) { "Not awaiting a flow mapping (state: $state)" }
        val req = requireNotNull(request)
        require(mapReq.mappings.isNotEmpty()) { "Map at least one flow to a station" }

        for (m in mapReq.mappings) {
            require(flows.any { it.forTv == m.forTv }) { "No forTV=${m.forTv} flow in this dump" }
            require(idPattern.matches(m.stationId)) { "Station id must match ${idPattern.pattern}" }
            require(m.stationName.isNotBlank()) { "Station name must not be blank" }
            require(registry.config(m.stationId) == null) { "Station id '${m.stationId}' is already hosted" }
        }
        require(mapReq.mappings.map { it.forTv }.toSet().size == mapReq.mappings.size) {
            "Each flow can map to only one station"
        }
        require(mapReq.mappings.map { it.stationId }.toSet().size == mapReq.mappings.size) {
            "Each station id can appear only once"
        }

        val schema = targetSchema(req)
        val stations = mapReq.mappings.map { StationConfig(id = it.stationId, name = it.stationName, logo = it.logo) }
        val stationByFlow = mapReq.mappings.associate { it.forTv to it.stationId }

        state = State.TRANSFORMING
        log("Transforming into '$schema': " + stationByFlow.entries.joinToString(", ") { (forTv, id) ->
            "forTV=$forTv (${if (forTv == 1) "TV" else "radio"}) -> $id"
        })
        log("Customers, contracts and contract lines are imported ONCE and shared by these stations.")

        thread(name = "migration-transform") {
            try {
                // The station rows must exist before anything can reference them.
                prepareGroupSchema(req.groupId, targetJdbcUrl(req), targetUsername(req), targetPassword(req), stations)

                connect(req).use { c ->
                    val scratch = scratchSchema(req)
                    // A DEFAULT DATABASE, even though every statement below names
                    // its schema. MySQL requires one for a multi-table DELETE and
                    // refuses with a bare "No database selected" otherwise - and
                    // this connection is brand new (the replay ran on another one),
                    // so it had none. The CLI never hit it only because it reuses
                    // the replay's connection, which the replayer leaves `USE`d.
                    // Belt and braces: the one offending statement was also rewritten
                    // (SenErpEnricher, "synthetic lines nothing references any more").
                    c.catalog = schema
                    summary = LegacyTransformer(
                        c, scratch, schema, stationByFlow,
                        log = { log("  $it") },
                        onStep = { done, total, label ->
                            progress = Progress("TRANSFORM", label, done.toLong(), total.toLong())
                        },
                        // Rides on the CURRENT step's Progress - onStep always
                        // fires first (step() resets the sub to 0/0), so the
                        // copy never resurrects a stale phase or label.
                        onSubProgress = { done, total ->
                            progress = progress?.copy(subDone = done, subTotal = total)
                        },
                    ).run()
                    req.senDirPath?.let { senDir ->
                        // ONCE per group: everything it fills (customers, contract
                        // periods, gift flags, the ERP item catalog) is group-scoped.
                        log("Enriching from the SEN (Oracle ERP) exports in $senDir ...")
                        SenErpEnricher(
                            c, schema,
                            log = { log("  $it") },
                            onStep = { done, total, label ->
                                progress = Progress("ENRICH", label, done.toLong(), total.toLong())
                            },
                        )
                            .enrich(File(senDir), apply = true, legacyScratchSchema = scratch)
                    } ?: log("(no SEN folder given - the ERP enrichment can run later from the CLI)")
                    c.createStatement().use { it.executeUpdate("DROP DATABASE `$scratch`") }
                    log("Scratch schema dropped.")
                }

                if (mapReq.addToYaml) {
                    hostLive(req, stations)
                }
                log("Migration finished.")
                progress = null
                state = State.DONE
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /**
     * Persists the result to server.yaml and hosts it LIVE - no restart needed;
     * the yaml entry makes it survive the next boot.
     */
    private fun hostLive(req: StartRequest, stations: List<StationConfig>) {
        val file = stationsYamlFile()
        val hosted = existingGroup
        if (hosted == null) {
            appendGroupToYaml(
                file = file,
                id = req.groupId,
                name = req.groupName ?: req.groupId,
                jdbcUrl = targetJdbcUrl(req),
                username = req.username,
                password = req.password,
                stations = stations.map { Triple(it.id, it.name, it.logo) },
            )
            registry.addGroup(
                GroupConfig(
                    id = req.groupId,
                    name = req.groupName ?: req.groupId,
                    jdbcUrl = targetJdbcUrl(req),
                    username = req.username,
                    password = req.password,
                    stations = stations,
                )
            )
            log("Group '${req.groupId}' with station(s) ${stations.joinToString { it.id }} added to ${file.path} and hosted LIVE.")
        } else {
            stations.forEach {
                appendStationToGroup(file, req.groupId, it.id, it.name, it.logo)
                registry.addStation(req.groupId, it)
            }
            log("Station(s) ${stations.joinToString { it.id }} added to group '${req.groupId}' in ${file.path} and hosted LIVE.")
        }
        log("They appear in user dropdowns at their next login; the super admin can grant access right away.")
    }

    private fun fail(e: Exception) {
        // A half-filled bar next to "FAILED" reads as "still working" - clear it.
        progress = null
        error = e.message ?: e.toString()
        log("FAILED: $error")
        // The message ALONE is close to useless on a migration: "No database
        // selected" tells you nothing about WHICH of a hundred statements said it.
        // The trace went nowhere before - not even to the server log - so a failure
        // could only be diagnosed by re-running the whole thing and guessing.
        migrationLog.error("Legacy migration FAILED", e)
        state = State.FAILED
    }

    private fun log(line: String) {
        logLines += line
    }

    /** The ADMIN connection (dump replay, schema creation) - the form's credentials. */
    private fun connect(req: StartRequest): Connection = DriverManager.getConnection(
        "jdbc:mysql://${req.host}:${req.port}/?useUnicode=true&characterEncoding=utf8" +
            "&zeroDateTimeBehavior=convertToNull&allowPublicKeyRetrieval=true&useSSL=false",
        req.username, req.password
    )

    // An existing group OWNS its database: its jdbcUrl and credentials win over
    // anything the form says, so a migration can never point two groups at one
    // schema (which would silently merge their customers).
    private fun targetJdbcUrl(req: StartRequest) = existingGroup?.jdbcUrl
        ?: "jdbc:mysql://${req.host}:${req.port}/${req.schema}?useUnicode=true&characterEncoding=utf8"

    private fun targetUsername(req: StartRequest) = existingGroup?.username ?: req.username

    private fun targetPassword(req: StartRequest) = existingGroup?.password ?: req.password

    private fun targetSchema(req: StartRequest): String = existingGroup
        ?.jdbcUrl?.substringAfterLast('/')?.substringBefore('?')
        ?: req.schema

    private fun scratchSchema(req: StartRequest) = "${targetSchema(req)}_scratch"

    private fun schemaExists(c: Connection, schema: String): Boolean =
        c.prepareStatement("SELECT 1 FROM information_schema.schemata WHERE schema_name = ?").use { ps ->
            ps.setString(1, schema)
            ps.executeQuery().use { it.next() }
        }
}
