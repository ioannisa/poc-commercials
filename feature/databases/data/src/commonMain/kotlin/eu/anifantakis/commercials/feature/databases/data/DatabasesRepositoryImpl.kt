package eu.anifantakis.commercials.feature.databases.data

import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.core.data.network.authenticatedJsonClient
import eu.anifantakis.commercials.core.data.network.remoteCall
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.domain.util.map
import eu.anifantakis.commercials.feature.databases.domain.DatabasesRepository
import eu.anifantakis.commercials.feature.databases.domain.HostedStation
import eu.anifantakis.commercials.feature.databases.domain.StationDeletion
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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

class DatabasesRepositoryImpl(private val session: AuthSession) : DatabasesRepository {

    private val httpClient by lazy { authenticatedJsonClient(session) }

    private val base: String get() = "${AppConfig.require().serverBaseUrl}/api/admin/stations"

    override suspend fun listStations(): DataResult<List<HostedStation>, RemoteError> = remoteCall {
        httpClient.get(base).body<List<HostedStationDto>>()
    }.map { list -> list.map { it.toDomain() } }

    override suspend fun deleteStation(
        id: String,
        hard: Boolean,
        confirmId: String,
    ): DataResult<StationDeletion, RemoteError> = remoteCall {
        val dto: DeleteStationResultDto = httpClient.post("$base/$id/delete") {
            contentType(ContentType.Application.Json)
            setBody(DeleteStationDto(if (hard) "hard" else "safe", confirmId))
        }.body()
        StationDeletion(dto.status, dto.grantsRemoved, dto.yamlEntryRemoved, dto.databaseDropped)
    }
}
