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
import eu.anifantakis.commercials.feature.timetable.data.dto.CreateProgramRequest
import eu.anifantakis.commercials.feature.timetable.data.dto.SetProgramActiveRequest
import eu.anifantakis.commercials.feature.timetable.data.dto.FinderSpotDto
import eu.anifantakis.commercials.feature.timetable.data.dto.ProgramDto
import eu.anifantakis.commercials.feature.timetable.data.dto.ReorderPlacementsRequest
import eu.anifantakis.commercials.feature.timetable.data.dto.CommercialsDto
import eu.anifantakis.commercials.feature.timetable.data.dto.ScheduleDto
import eu.anifantakis.commercials.feature.timetable.data.dto.UpdateProgramRequest
import eu.anifantakis.commercials.feature.timetable.data.mappers.toDomain
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemoteFinderDataSource
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemotePlacementsDataSource
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemoteProgramsDataSource
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemoteScheduleDataSource
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.domain.model.MonthSchedule
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import eu.anifantakis.commercials.feature.timetable.domain.model.Program
import eu.anifantakis.commercials.feature.timetable.domain.model.ScheduleFilter
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/*
 * Base URL, bearer token and the station parameter all come from
 * [ApiHttpClient] - these classes carry only paths, payloads and mapping.
 */

class RemoteScheduleDataSourceImpl(private val api: ApiHttpClient) : RemoteScheduleDataSource {

    override suspend fun getBreaks(
        year: Int,
        month: Int,
        mode: GridViewMode,
    ): DataResult<List<BreakSlotInfo>, DataError.Network> =
        api.get<List<BreakSlotDto>>(
            "/api/breaks", "year" to year, "month" to month, "mode" to mode.name,
        ).map { list -> list.map { it.toDomain() } }

    override suspend fun getMonth(
        year: Int,
        month: Int,
        mode: GridViewMode,
        filter: ScheduleFilter?,
    ): DataResult<MonthSchedule, DataError.Network> =
        api.get<ScheduleDto>(
            "/api/schedule",
            "year" to year, "month" to month, "mode" to mode.name,
            // "Προβολή Βάσει…" - at most ONE of these is non-null (the server
            // 400s on combinations); nulls are dropped by the client.
            "programId" to (filter as? ScheduleFilter.ByProgram)?.programId,
            "partyCode" to (filter as? ScheduleFilter.ByParty)?.code,
            "partyKind" to (filter as? ScheduleFilter.ByParty)?.kind?.wire,
            "lineId" to (filter as? ScheduleFilter.ByContract)?.lineId,
            "spotId" to (filter as? ScheduleFilter.BySpot)?.spotId,
        ).map { it.toDomain() }

    override suspend fun getCommercials(
        year: Int,
        month: Int,
        date: LocalDate?,
        time: LocalTime?,
    ): DataResult<Map<Pair<LocalTime, LocalDate>, List<PlacedCommercial>>, DataError.Network> =
        api.get<CommercialsDto>(
            "/api/schedule/commercials",
            "year" to year, "month" to month,
            "date" to date?.toString(),
            "time" to time?.hhMm(),
        ).map { it.toDomain() }
}

class RemotePlacementsDataSourceImpl(private val api: ApiHttpClient) : RemotePlacementsDataSource {

    override suspend fun add(
        spotId: Long,
        time: LocalTime,
        date: LocalDate,
        programId: Long?,
    ): DataResult<PlacedCommercial, DataError.Network> =
        api.post<AddPlacementRequest, CommercialDto>(
            "/api/schedule/placements",
            AddPlacementRequest(spotId, time.hhMm(), date.toString(), programId),
        ).map { it.toDomain() }

    override suspend fun remove(placementId: Long): EmptyDataResult<DataError.Network> =
        api.deleteEmpty("/api/schedule/placements/$placementId")

    override suspend fun reorder(
        time: LocalTime,
        date: LocalDate,
        orderedIds: List<Long>,
    ): EmptyDataResult<DataError.Network> =
        api.putEmpty(
            "/api/schedule/placements/order",
            ReorderPlacementsRequest(time.hhMm(), date.toString(), orderedIds),
        )
}

class RemoteProgramsDataSourceImpl(private val api: ApiHttpClient) : RemoteProgramsDataSource {

    override suspend fun list(): DataResult<List<Program>, DataError.Network> =
        api.get<List<ProgramDto>>("/api/schedule/programs")
            .map { list -> list.map { it.toDomain() } }

    override suspend fun listAll(): DataResult<List<Program>, DataError.Network> =
        api.get<List<ProgramDto>>("/api/schedule/programs", "all" to true)
            .map { list -> list.map { it.toDomain() } }

    override suspend fun setActive(id: Long, active: Boolean): EmptyDataResult<DataError.Network> =
        api.putEmpty("/api/schedule/programs/$id/active", SetProgramActiveRequest(active))

    override suspend fun create(name: String, colorArgb: Int?): DataResult<Program, DataError.Network> =
        api.post<CreateProgramRequest, ProgramDto>(
            "/api/schedule/programs",
            CreateProgramRequest(name, colorArgb),
        ).map { it.toDomain() }

    override suspend fun update(id: Long, name: String?, colorArgb: Int?): EmptyDataResult<DataError.Network> =
        api.putEmpty("/api/schedule/programs/$id", UpdateProgramRequest(name, colorArgb))

    override suspend fun remove(id: Long): EmptyDataResult<DataError.Network> =
        api.deleteEmpty("/api/schedule/programs/$id")
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

/**
 * The wire form of a break's time. `LocalTime.toString()` would emit seconds
 * when they are non-zero ("12:20:00" vs "12:20"), and the server's HH:mm parser
 * rejects that - so the format is pinned here rather than left to the default.
 */
private fun LocalTime.hhMm(): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
