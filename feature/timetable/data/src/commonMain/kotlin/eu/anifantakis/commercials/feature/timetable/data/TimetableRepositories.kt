package eu.anifantakis.commercials.feature.timetable.data

import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.core.data.network.authenticatedJsonClient
import eu.anifantakis.commercials.core.data.network.dataCall
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.core.domain.util.asEmptyDataResult
import eu.anifantakis.commercials.core.domain.util.map
import eu.anifantakis.commercials.feature.timetable.data.dto.AddPlacementRequest
import eu.anifantakis.commercials.feature.timetable.data.dto.BreakSlotDto
import eu.anifantakis.commercials.feature.timetable.data.dto.CommercialDto
import eu.anifantakis.commercials.feature.timetable.data.dto.ContractLineDto
import eu.anifantakis.commercials.feature.timetable.data.dto.FinderSpotDto
import eu.anifantakis.commercials.feature.timetable.data.dto.ReorderPlacementsRequest
import eu.anifantakis.commercials.feature.timetable.data.dto.ScheduleDto
import eu.anifantakis.commercials.feature.timetable.data.mappers.toDomain
import eu.anifantakis.commercials.feature.timetable.domain.FinderRepository
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.domain.model.MonthSchedule
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDate

private fun AuthSession.stationId(): String = selectedStation?.id ?: ""

private fun baseUrl(): String = AppConfig.require().serverBaseUrl

/** Read side: breaks + the month grid, station-scoped via the session. */
class ScheduleRepositoryImpl(private val session: AuthSession) : ScheduleRepository {

    private val client by lazy { authenticatedJsonClient(session) }

    override suspend fun getBreaks(): DataResult<List<BreakSlotInfo>, DataError.Network> = dataCall {
        client.get("${baseUrl()}/api/breaks") {
            parameter("station", session.stationId())
        }.body<List<BreakSlotDto>>()
    }.map { list -> list.map { it.toDomain() } }

    override suspend fun getMonth(year: Int, month: Int): DataResult<MonthSchedule, DataError.Network> = dataCall {
        client.get("${baseUrl()}/api/schedule") {
            parameter("year", year)
            parameter("month", month)
            parameter("station", session.stationId())
        }.body<ScheduleDto>()
    }.map { it.toDomain() }
}

/** Write side: the 'a'/'r' keys and the detail screen's reorder. */
class PlacementsRepositoryImpl(private val session: AuthSession) : PlacementsRepository {

    private val client by lazy { authenticatedJsonClient(session) }

    override suspend fun add(
        spotId: Long,
        breakId: Long,
        date: LocalDate,
    ): DataResult<PlacedCommercial, DataError.Network> = dataCall {
        client.post("${baseUrl()}/api/schedule/placements?station=${session.stationId()}") {
            contentType(ContentType.Application.Json)
            setBody(AddPlacementRequest(spotId, breakId, date.toString()))
        }.body<CommercialDto>()
    }.map { it.toDomain() }

    override suspend fun remove(placementId: Long): EmptyDataResult<DataError.Network> = dataCall {
        val resp = client.delete("${baseUrl()}/api/schedule/placements/$placementId?station=${session.stationId()}")
        check(resp.status.isSuccess())
    }.asEmptyDataResult()

    override suspend fun reorder(
        breakId: Long,
        date: LocalDate,
        orderedIds: List<Long>,
    ): EmptyDataResult<DataError.Network> = dataCall {
        val resp = client.put("${baseUrl()}/api/schedule/placements/order?station=${session.stationId()}") {
            contentType(ContentType.Application.Json)
            setBody(ReorderPlacementsRequest(breakId, date.toString(), orderedIds))
        }
        check(resp.status.isSuccess())
    }.asEmptyDataResult()
}

/** The finder console's drill-down: party -> contract lines -> spots. */
class FinderRepositoryImpl(private val session: AuthSession) : FinderRepository {

    private val client by lazy { authenticatedJsonClient(session) }

    override suspend fun contractLines(
        clientCode: String,
        kind: PartyKind,
    ): DataResult<List<ContractLine>, DataError.Network> = dataCall {
        client.get("${baseUrl()}/api/finder/contracts") {
            parameter("station", session.stationId())
            parameter("kind", kind.wire)
            parameter("clientCode", clientCode)
        }.body<List<ContractLineDto>>()
    }.map { list -> list.map { it.toDomain() } }

    override suspend fun lineSpots(lineId: Long): DataResult<List<ContractLineSpot>, DataError.Network> = dataCall {
        client.get("${baseUrl()}/api/finder/spots") {
            parameter("station", session.stationId())
            parameter("lineId", lineId)
        }.body<List<FinderSpotDto>>()
    }.map { list -> list.map { it.toDomain() } }
}
