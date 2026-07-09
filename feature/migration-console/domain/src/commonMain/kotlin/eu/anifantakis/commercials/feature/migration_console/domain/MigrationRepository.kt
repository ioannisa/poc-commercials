package eu.anifantakis.commercials.feature.migration_console.domain

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError

data class MigrationStart(
    val dumpPath: String,
    val host: String = "localhost",
    val port: Int = 3306,
    val username: String,
    val password: String,
    val schema: String,
    val createSchema: Boolean = true,
    /**
     * Optional SERVER folder with the SEN (Oracle ERP) table exports (one
     * tab-delimited file per Oracle table). When set, the migration follows
     * the transform with the ERP enrichment: real customer names/VAT/contacts,
     * real contract periods, corrected gift flags.
     */
    val senDirPath: String? = null,
)

data class MigrationFlowChoice(
    val forTv: Int,
    val stationId: String = "",
    val stationName: String = "",
    val addToYaml: Boolean = true,
)

data class MigrationFlowInfo(val forTv: Int, val spots: Long, val placements: Long)

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
)

data class MigrationStatus(
    val state: String = "IDLE",
    val log: List<String> = emptyList(),
    val flows: List<MigrationFlowInfo> = emptyList(),
    val summary: MigrationSummary? = null,
    val error: String? = null,
    val schema: String? = null,
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
    suspend fun chooseFlow(choice: MigrationFlowChoice): DataResult<MigrationStatus, RemoteError>
    suspend fun reset(): DataResult<MigrationStatus, RemoteError>

    /** Lists directories + .sql files ON THE SERVER (null = server home dir). */
    suspend fun browse(path: String?): DataResult<BrowseListing, RemoteError>
}
