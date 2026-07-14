package eu.anifantakis.commercials.feature.migration_console.domain

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError

data class MigrationStart(
    val dumpPath: String,
    val host: String = "localhost",
    val port: Int = 3306,
    val username: String,
    val password: String,
    /**
     * The target GROUP: an already-hosted group id, or a new one to create.
     * A legacy database is ONE COMPANY's - its TV and radio flows share the
     * customers and the contracts - so it migrates into one group database
     * whose stations are those flows.
     */
    val groupId: String,
    /** Display name - only used when the group is new. */
    val groupName: String? = null,
    /** Target schema - only used when the group is new (an existing one owns its own). */
    val schema: String = "",
    val createSchema: Boolean = true,
    /**
     * Optional SERVER folder with the SEN (Oracle ERP) table exports (one
     * tab-delimited file per Oracle table). When set, the migration follows
     * the transform with the ERP enrichment: real customer names/VAT/contacts,
     * real contract periods, corrected gift flags. It runs ONCE for the group.
     */
    val senDirPath: String? = null,
)

/** One legacy flow (forTV) and the station of the group it becomes. */
data class MigrationFlowMapping(
    val forTv: Int,
    val stationId: String = "",
    val stationName: String = "",
    val logo: String? = null,
)

/**
 * The flow -> station map. A LIST, not a choice: one dump fills every station of
 * the group in a single run. Leaving a flow out simply does not migrate it.
 */
data class MigrationMapping(
    val mappings: List<MigrationFlowMapping>,
    val addToYaml: Boolean = true,
)

data class MigrationFlowInfo(val forTv: Int, val spots: Long, val placements: Long)

/** A group the migration can target. */
data class MigrationGroup(val id: String, val name: String, val schema: String)

/** What one station received (one dump now fills several). */
data class MigrationStationTally(
    val stationId: String,
    val forTv: Int,
    val spots: Int,
    val placements: Int,
)

data class MigrationSummary(
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
    val stations: List<MigrationStationTally> = emptyList(),
)

data class MigrationStatus(
    val state: String = "IDLE",
    val log: List<String> = emptyList(),
    val flows: List<MigrationFlowInfo> = emptyList(),
    val summary: MigrationSummary? = null,
    val error: String? = null,
    val schema: String? = null,
    /** The hosted groups, offered as migration targets. */
    val groups: List<MigrationGroup> = emptyList(),
)

data class BrowseEntry(val name: String, val isDir: Boolean, val sizeBytes: Long = 0)

data class BrowseListing(val path: String, val parent: String? = null, val entries: List<BrowseEntry> = emptyList())

/**
 * Super-admin legacy migration. The heavy work (dump replay, transformation)
 * runs ON THE SERVER - the dump path refers to the server's filesystem;
 * the client only steers and polls.
 */
interface MigrationRepository {
    suspend fun status(): DataResult<MigrationStatus, RemoteError>
    suspend fun start(request: MigrationStart): DataResult<MigrationStatus, RemoteError>
    suspend fun chooseMapping(mapping: MigrationMapping): DataResult<MigrationStatus, RemoteError>
    suspend fun reset(): DataResult<MigrationStatus, RemoteError>

    /** Lists directories + .sql files ON THE SERVER (null = server home dir). */
    suspend fun browse(path: String?): DataResult<BrowseListing, RemoteError>
}
