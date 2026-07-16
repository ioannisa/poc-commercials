package eu.anifantakis.commercials.migration

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class MigrationStartDto(
    val dumpPath: String,
    val host: String = "localhost",
    val port: Int = 3306,
    val username: String,
    val password: String,
    /** Target GROUP: an already-hosted id, or a new one to create. */
    val groupId: String,
    /** Display name - only used when the group is new. */
    val groupName: String? = null,
    /** Target schema - only used when the group is new (an existing group owns its own). */
    val schema: String = "",
    val createSchema: Boolean = true,
    /** Optional SERVER folder of SEN (Oracle ERP) exports - enriches after the transform. */
    val senDirPath: String? = null,
)

/** One legacy flow (forTV) and the station of the group it becomes. */
@Serializable
data class MigrationFlowMappingDto(
    val forTv: Int,
    val stationId: String = "",
    val stationName: String = "",
    val logo: String? = null,
)

/**
 * The flow -> station mapping. A LIST, not a choice: one dump fills every
 * station of the group in a single run, and they share its customers and
 * contracts. Leaving a flow out simply does not migrate it.
 */
@Serializable
data class MigrationMappingDto(
    val mappings: List<MigrationFlowMappingDto>,
    val addToYaml: Boolean = true,
)

@Serializable
data class MigrationFlowInfoDto(val forTv: Int, val spots: Long, val placements: Long)

/** A group the wizard can migrate into. */
@Serializable
data class MigrationGroupDto(val id: String, val name: String, val schema: String)

/** What one station actually received (one dump now fills several). */
@Serializable
data class MigrationStationTallyDto(
    val stationId: String,
    val forTv: Int,
    val spots: Int,
    val placements: Int,
)

@Serializable
data class MigrationSummaryDto(
    val breaks: Int,
    val customers: Int,
    val customersSynthetic: Int,
    val contracts: Int,
    val contractsSynthetic: Int,
    val contractLines: Int,
    val spots: Int,
    val placements: Int,
    val flowComments: Int,
    val printAudits: Int,
    val dateRange: String,
    val dumpScheduleRows: Long = 0,
    val otherFlowRows: Long = 0,
    val orphanedRows: Long = 0,
    val zeroDateRows: Long = 0,
    val programs: Int = 0,
    val triangularContracts: Int = 0,
    val endClientsSynthesized: Int = 0,
    val emails: Int = 0,
    val emailBodiesKept: Int = 0,
    val zones: Int = 0,
    val zoneFillers: Int = 0,
    val stations: List<MigrationStationTallyDto> = emptyList(),
)

@Serializable
data class MigrationStatusDto(
    val state: String,
    val log: List<String>,
    /** Null when nothing measurable is running - the UI then shows no bar. */
    val progress: MigrationProgressDto? = null,
    val flows: List<MigrationFlowInfoDto> = emptyList(),
    val summary: MigrationSummaryDto? = null,
    val error: String? = null,
    val schema: String? = null,
    /** The hosted groups, so the wizard can offer them as targets. */
    val groups: List<MigrationGroupDto> = emptyList(),
)

/**
 * Honest progress: [done] of [total], in the phase's own unit (megabytes of the
 * dump while replaying, steps while transforming/enriching). [total] = 0 means
 * there IS no honest total - render an indeterminate bar, never a guessed one.
 */
@Serializable
data class MigrationProgressDto(
    val phase: String,
    val label: String,
    val done: Long,
    val total: Long,
)

@Serializable
data class BrowseEntryDto(val name: String, val isDir: Boolean, val sizeBytes: Long = 0)

@Serializable
data class BrowseDto(val path: String, val parent: String? = null, val entries: List<BrowseEntryDto>)

/**
 * Drives the legacy-dump migration from the in-app Migration screen.
 * Super administrator only; the heavy lifting happens server-side in
 * [MigrationService] (the dump file path is a path on the SERVER machine).
 * Validation errors surface as 400 via the host's StatusPages.
 *
 * [requireAdmin] is the HOST's guard: security (who is the super admin, how
 * the bearer principal works) stays the server's concern - this module only
 * demands that every endpoint pass it.
 */
fun Route.migrationRoutes(
    migration: MigrationService,
    requireAdmin: suspend ApplicationCall.() -> Boolean,
) {
    route("/api/admin/migration") {

        /**
         * Return the current migration state, log, progress, flows, summary and available target groups.
         *
         * Tag: Migration
         */
        get("/status") {
            if (!call.requireAdmin()) return@get
            call.respond(migration.snapshot().toDto())
        }

        /**
         * Browse a server-side directory, listing subfolders and .sql/.csv/.tsv dump/export files.
         *
         * Server-side file browser backing the "Browse" buttons: the dump and
         * the SEN export folder live on the SERVER's filesystem, so that's
         * what gets browsed - works identically from web and desktop clients.
         * Read-only listing of directories, .sql dumps and .csv/.tsv exports,
         * super admin only (the same trust level as the migration itself,
         * which reads arbitrary paths).
         *
         * Tag: Migration
         */
        get("/browse") {
            if (!call.requireAdmin()) return@get
            val requested = call.request.queryParameters["path"]
            val dir = java.io.File(requested ?: System.getProperty("user.home") ?: "/")
            if (!dir.isDirectory) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Not a directory: ${dir.path}"))
                return@get
            }
            val shownExtensions = listOf(".sql", ".csv", ".tsv")
            val entries = (dir.listFiles() ?: emptyArray())
                .filter { !it.name.startsWith(".") }
                .filter { f -> f.isDirectory || shownExtensions.any { f.name.endsWith(it, ignoreCase = true) } }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .map { BrowseEntryDto(it.name, it.isDirectory, if (it.isFile) it.length() else 0) }
            call.respond(BrowseDto(path = dir.absolutePath, parent = dir.absoluteFile.parent, entries = entries))
        }

        /**
         * Start a legacy-dump migration into the target group from the given MySQL dump.
         *
         * Tag: Migration
         */
        post("/start") {
            if (!call.requireAdmin()) return@post
            val req = call.receive<MigrationStartDto>()
            withContext(Dispatchers.IO) {
                migration.start(
                    MigrationService.StartRequest(
                        dumpPath = req.dumpPath.trim(),
                        host = req.host.trim(),
                        port = req.port,
                        username = req.username.trim(),
                        password = req.password,
                        groupId = req.groupId.trim(),
                        groupName = req.groupName?.trim()?.ifEmpty { null },
                        schema = req.schema.trim(),
                        createSchema = req.createSchema,
                        senDirPath = req.senDirPath?.trim()?.ifEmpty { null },
                    )
                )
            }
            call.respond(migration.snapshot().toDto())
        }

        /**
         * Apply the legacy flow-to-station mapping and continue the migration run.
         *
         * Tag: Migration
         */
        post("/flow") {
            if (!call.requireAdmin()) return@post
            val req = call.receive<MigrationMappingDto>()
            withContext(Dispatchers.IO) {
                migration.chooseMapping(
                    MigrationService.MappingRequest(
                        mappings = req.mappings.map {
                            MigrationService.FlowMapping(
                                forTv = it.forTv,
                                stationId = it.stationId.trim(),
                                stationName = it.stationName.trim(),
                                logo = it.logo?.trim()?.ifEmpty { null },
                            )
                        },
                        addToYaml = req.addToYaml,
                    )
                )
            }
            call.respond(migration.snapshot().toDto())
        }

        /**
         * Reset the migration back to its idle initial state.
         *
         * Tag: Migration
         */
        post("/reset") {
            if (!call.requireAdmin()) return@post
            migration.reset()
            call.respond(migration.snapshot().toDto())
        }
    }
}

private fun MigrationService.Snapshot.toDto() = MigrationStatusDto(
    state = state.name,
    log = log,
    progress = progress?.let { MigrationProgressDto(it.phase, it.label, it.done, it.total) },
    flows = flows.map { MigrationFlowInfoDto(it.forTv, it.spots, it.placements) },
    summary = summary?.let {
        MigrationSummaryDto(
            it.breaks, it.customers, it.customersSynthetic, it.contracts, it.contractsSynthetic,
            it.contractLines, it.spots, it.placements, it.flowComments, it.printAudits, it.dateRange,
            it.dumpScheduleRows, it.otherFlowRows, it.orphanedRows, it.zeroDateRows, it.programs,
            it.triangularContracts, it.endClientsSynthesized, it.emails, it.emailBodiesKept,
            it.zones, it.zoneFillers,
            it.stations.map { st -> MigrationStationTallyDto(st.stationId, st.forTv, st.spots, st.placements) },
        )
    },
    error = error,
    schema = schema,
    groups = groups.map { MigrationGroupDto(it.id, it.name, it.schema) },
)
