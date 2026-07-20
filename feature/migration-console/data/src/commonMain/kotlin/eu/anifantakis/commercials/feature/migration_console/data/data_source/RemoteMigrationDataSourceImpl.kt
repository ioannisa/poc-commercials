package eu.anifantakis.commercials.feature.migration_console.data.data_source

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.domain.util.map
import eu.anifantakis.commercials.feature.migration_console.domain.BrowseEntry
import eu.anifantakis.commercials.feature.migration_console.domain.BrowseListing
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationFlowInfo
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationGroup
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationMapping
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStart
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStationTally
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationProgress
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStatus
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationSummary
import eu.anifantakis.commercials.feature.migration_console.domain.data_source.RemoteMigrationDataSource
import kotlinx.serialization.Serializable

@Serializable
private data class StartDto(
    val dumpPath: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val groupId: String,
    val groupName: String? = null,
    val schema: String = "",
    val createSchema: Boolean,
    val senDirPath: String? = null,
)

@Serializable
private data class FlowMappingDto(
    val forTv: Int,
    val stationId: String = "",
    val stationName: String = "",
    val logo: String? = null,
)

@Serializable
private data class MappingDto(
    val mappings: List<FlowMappingDto>,
    val addToYaml: Boolean = true,
)

@Serializable
private data class FlowInfoDto(val forTv: Int, val spots: Long, val placements: Long)

@Serializable
private data class GroupDto(val id: String, val name: String, val schema: String)

@Serializable
private data class StationTallyDto(
    val stationId: String,
    val forTv: Int,
    val spots: Int,
    val placements: Int,
)

@Serializable
private data class ProgressDto(
    val phase: String,
    val label: String,
    val done: Long,
    val total: Long,
    /** Within-step progress; 0 total = the running step reports none. */
    val subDone: Long = 0,
    val subTotal: Long = 0,
)

@Serializable
private data class SummaryDto(
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
    val stations: List<StationTallyDto> = emptyList(),
)

@Serializable
private data class StatusDto(
    val state: String = "IDLE",
    val log: List<String> = emptyList(),
    val progress: ProgressDto? = null,
    val flows: List<FlowInfoDto> = emptyList(),
    val summary: SummaryDto? = null,
    val error: String? = null,
    val schema: String? = null,
    val groups: List<GroupDto> = emptyList(),
)

@Serializable
private data class BrowseEntryDto(val name: String, val isDir: Boolean, val sizeBytes: Long = 0)

@Serializable
private data class BrowseListingDto(
    val path: String,
    val parent: String? = null,
    val entries: List<BrowseEntryDto> = emptyList(),
)

private fun SummaryDto.toDomain() = MigrationSummary(
    breaks, customers, customersSynthetic, contracts, contractsSynthetic, contractLines,
    spots, placements, flowComments, printAudits, dateRange,
    dumpScheduleRows, otherFlowRows, orphanedRows, zeroDateRows, programs,
    stations.map { MigrationStationTally(it.stationId, it.forTv, it.spots, it.placements) },
)

private fun StatusDto.toDomain() = MigrationStatus(
    state = state,
    log = log,
    progress = progress?.let { MigrationProgress(it.phase, it.label, it.done, it.total, it.subDone, it.subTotal) },
    flows = flows.map { MigrationFlowInfo(it.forTv, it.spots, it.placements) },
    summary = summary?.toDomain(),
    error = error,
    schema = schema,
    groups = groups.map { MigrationGroup(it.id, it.name, it.schema) },
)

private fun BrowseListingDto.toDomain() =
    BrowseListing(path, parent, entries.map { BrowseEntry(it.name, it.isDir, it.sizeBytes) })

class RemoteMigrationDataSourceImpl(private val api: ApiHttpClient) : RemoteMigrationDataSource {

    override suspend fun status(): DataResult<MigrationStatus, RemoteError> =
        api.getRemote<StatusDto>("/api/admin/migration/status").map { it.toDomain() }

    override suspend fun start(request: MigrationStart): DataResult<MigrationStatus, RemoteError> =
        api.postRemote<StartDto, StatusDto>(
            "/api/admin/migration/start",
            StartDto(
                request.dumpPath, request.host, request.port,
                request.username, request.password,
                request.groupId, request.groupName, request.schema, request.createSchema,
                request.senDirPath,
            ),
        ).map { it.toDomain() }

    override suspend fun chooseMapping(mapping: MigrationMapping): DataResult<MigrationStatus, RemoteError> =
        api.postRemote<MappingDto, StatusDto>(
            "/api/admin/migration/flow",
            MappingDto(
                mapping.mappings.map { FlowMappingDto(it.forTv, it.stationId, it.stationName, it.logo) },
                mapping.addToYaml,
            ),
        ).map { it.toDomain() }

    override suspend fun reset(): DataResult<MigrationStatus, RemoteError> =
        api.postRemote<StatusDto, StatusDto>("/api/admin/migration/reset").map { it.toDomain() }

    override suspend fun browse(path: String?): DataResult<BrowseListing, RemoteError> =
        api.getRemote<BrowseListingDto>("/api/admin/migration/browse", "path" to path).map { it.toDomain() }
}
