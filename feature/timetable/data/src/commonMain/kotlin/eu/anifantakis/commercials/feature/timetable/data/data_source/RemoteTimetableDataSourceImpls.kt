package eu.anifantakis.commercials.feature.timetable.data.data_source

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.core.domain.util.map
import eu.anifantakis.commercials.feature.timetable.data.dto.AddPlacementRequest
import eu.anifantakis.commercials.feature.timetable.data.dto.BreakSlotDto
import eu.anifantakis.commercials.feature.timetable.data.dto.CommercialDto
import eu.anifantakis.commercials.feature.timetable.data.dto.ContractLineDto
import eu.anifantakis.commercials.feature.timetable.data.dto.FinderSpotDto
import eu.anifantakis.commercials.feature.timetable.data.dto.ReorderPlacementsRequest
import eu.anifantakis.commercials.feature.timetable.data.dto.ScheduleDto
import eu.anifantakis.commercials.feature.timetable.data.mappers.toDomain
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemoteFinderDataSource
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemotePlacementsDataSource
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemoteScheduleDataSource
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.domain.model.MonthSchedule
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import kotlinx.datetime.LocalDate

/*
 * Base URL, bearer token and the station parameter all come from
 * [ApiHttpClient] - these classes carry only paths, payloads and mapping.
 */

class RemoteScheduleDataSourceImpl(private val api: ApiHttpClient) : RemoteScheduleDataSource {

    override suspend fun getBreaks(): DataResult<List<BreakSlotInfo>, DataError.Network> =
        api.get<List<BreakSlotDto>>("/api/breaks")
            .map { list -> list.map { it.toDomain() } }

    override suspend fun getMonth(year: Int, month: Int): DataResult<MonthSchedule, DataError.Network> =
        api.get<ScheduleDto>("/api/schedule", "year" to year, "month" to month)
            .map { it.toDomain() }
}

class RemotePlacementsDataSourceImpl(private val api: ApiHttpClient) : RemotePlacementsDataSource {

    override suspend fun add(
        spotId: Long,
        breakId: Long,
        date: LocalDate,
    ): DataResult<PlacedCommercial, DataError.Network> =
        api.post<AddPlacementRequest, CommercialDto>(
            "/api/schedule/placements",
            AddPlacementRequest(spotId, breakId, date.toString()),
        ).map { it.toDomain() }

    override suspend fun remove(placementId: Long): EmptyDataResult<DataError.Network> =
        api.deleteEmpty("/api/schedule/placements/$placementId")

    override suspend fun reorder(
        breakId: Long,
        date: LocalDate,
        orderedIds: List<Long>,
    ): EmptyDataResult<DataError.Network> =
        api.putEmpty(
            "/api/schedule/placements/order",
            ReorderPlacementsRequest(breakId, date.toString(), orderedIds),
        )
}

class RemoteFinderDataSourceImpl(private val api: ApiHttpClient) : RemoteFinderDataSource {

    override suspend fun contractLines(
        clientCode: String,
        kind: PartyKind,
    ): DataResult<List<ContractLine>, DataError.Network> =
        api.get<List<ContractLineDto>>(
            "/api/finder/contracts",
            "kind" to kind.wire,
            "clientCode" to clientCode,
        ).map { list -> list.map { it.toDomain() } }

    override suspend fun lineSpots(lineId: Long): DataResult<List<ContractLineSpot>, DataError.Network> =
        api.get<List<FinderSpotDto>>("/api/finder/spots", "lineId" to lineId)
            .map { list -> list.map { it.toDomain() } }
}
