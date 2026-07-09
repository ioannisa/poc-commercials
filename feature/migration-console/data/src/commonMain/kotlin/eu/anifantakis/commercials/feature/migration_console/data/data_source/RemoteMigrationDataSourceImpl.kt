package eu.anifantakis.commercials.feature.migration_console.data.data_source

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.domain.util.map
import eu.anifantakis.commercials.feature.migration_console.domain.BrowseEntry
import eu.anifantakis.commercials.feature.migration_console.domain.BrowseListing
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationFlowChoice
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationFlowInfo
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStart
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
    val schema: String,
    val createSchema: Boolean,
    val senDirPath: String? = null,
)

@Serializable
private data class FlowChoiceDto(
    val forTv: Int,
    val stationId: String = "",
    val stationName: String = "",
    val addToYaml: Boolean = true,
)

@Serializable
private data class FlowInfoDto(val forTv: Int, val spots: Long, val placements: Long)

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
)

@Serializable
private data class StatusDto(
    val state: String = "IDLE",
    val log: List<String> = emptyList(),
    val flows: List<FlowInfoDto> = emptyList(),
    val summary: SummaryDto? = null,
    val error: String? = null,
    val schema: String? = null,
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
)

private fun StatusDto.toDomain() = MigrationStatus(
    state = state,
    log = log,
    flows = flows.map { MigrationFlowInfo(it.forTv, it.spots, it.placements) },
    summary = summary?.toDomain(),
    error = error,
    schema = schema,
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
                request.username, request.password, request.schema, request.createSchema,
                request.senDirPath,
            ),
        ).map { it.toDomain() }

    override suspend fun chooseFlow(choice: MigrationFlowChoice): DataResult<MigrationStatus, RemoteError> =
        api.postRemote<FlowChoiceDto, StatusDto>(
            "/api/admin/migration/flow",
            FlowChoiceDto(choice.forTv, choice.stationId, choice.stationName, choice.addToYaml),
        ).map { it.toDomain() }

    override suspend fun reset(): DataResult<MigrationStatus, RemoteError> =
        api.postRemote<StatusDto, StatusDto>("/api/admin/migration/reset").map { it.toDomain() }

    override suspend fun browse(path: String?): DataResult<BrowseListing, RemoteError> =
        api.getRemote<BrowseListingDto>("/api/admin/migration/browse", "path" to path).map { it.toDomain() }
}
