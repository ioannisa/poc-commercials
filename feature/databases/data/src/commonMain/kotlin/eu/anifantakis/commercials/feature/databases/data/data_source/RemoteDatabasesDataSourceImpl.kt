package eu.anifantakis.commercials.feature.databases.data.data_source

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.domain.util.map
import eu.anifantakis.commercials.feature.databases.domain.HostedStation
import eu.anifantakis.commercials.feature.databases.domain.StationDeletion
import eu.anifantakis.commercials.feature.databases.domain.data_source.RemoteDatabasesDataSource
import kotlinx.serialization.Serializable

@Serializable
private data class HostedStationDto(
    val id: String,
    val name: String,
    val database: String,
    val reachable: Boolean = true,
    val placements: Long? = null,
    val dateRange: String? = null,
)

@Serializable
private data class DeleteStationDto(val mode: String, val confirmId: String)

@Serializable
private data class DeleteStationResultDto(
    val status: String = "",
    val grantsRemoved: Int = 0,
    val yamlEntryRemoved: Boolean = false,
    val databaseDropped: Boolean = false,
)

private fun HostedStationDto.toDomain() = HostedStation(id, name, database, reachable, placements, dateRange)

class RemoteDatabasesDataSourceImpl(private val api: ApiHttpClient) : RemoteDatabasesDataSource {

    override suspend fun listStations(): DataResult<List<HostedStation>, RemoteError> =
        api.getRemote<List<HostedStationDto>>("/api/admin/stations")
            .map { list -> list.map { it.toDomain() } }

    override suspend fun deleteStation(
        id: String,
        hard: Boolean,
        confirmId: String,
    ): DataResult<StationDeletion, RemoteError> =
        api.postRemote<DeleteStationDto, DeleteStationResultDto>(
            "/api/admin/stations/$id/delete",
            DeleteStationDto(if (hard) "hard" else "safe", confirmId),
        ).map { dto -> StationDeletion(dto.status, dto.grantsRemoved, dto.yamlEntryRemoved, dto.databaseDropped) }
}
