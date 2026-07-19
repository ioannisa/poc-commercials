package eu.anifantakis.commercials.feature.timetable.data

import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.feature.timetable.domain.FinderRepository
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.feature.timetable.domain.ProgramsRepository
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemoteFinderDataSource
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemotePlacementsDataSource
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemoteProgramsDataSource
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemoteScheduleDataSource
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.domain.model.MonthSchedule
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import eu.anifantakis.commercials.feature.timetable.domain.model.Program
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/*
 * Organizers only: every wire call lives in the Remote*DataSource impls
 * (data_source/); these compose them and hold whatever policy shows up
 * later (caching, merging, retries).
 */

class ScheduleRepositoryImpl(
    private val remoteDataSource: RemoteScheduleDataSource,
) : ScheduleRepository {

    override suspend fun getBreaks(
        year: Int,
        month: Int,
        mode: GridViewMode,
    ): DataResult<List<BreakSlotInfo>, DataError.Network> = remoteDataSource.getBreaks(year, month, mode)

    override suspend fun getMonth(
        year: Int,
        month: Int,
        mode: GridViewMode,
    ): DataResult<MonthSchedule, DataError.Network> = remoteDataSource.getMonth(year, month, mode)

    override suspend fun getCommercials(
        year: Int,
        month: Int,
        date: LocalDate?,
        time: LocalTime?,
    ): DataResult<Map<Pair<LocalTime, LocalDate>, List<PlacedCommercial>>, DataError.Network> =
        remoteDataSource.getCommercials(year, month, date, time)
}

class PlacementsRepositoryImpl(
    private val remoteDataSource: RemotePlacementsDataSource,
) : PlacementsRepository {

    override suspend fun add(
        spotId: Long,
        time: LocalTime,
        date: LocalDate,
        programId: Long?,
    ): DataResult<PlacedCommercial, DataError.Network> = remoteDataSource.add(spotId, time, date, programId)

    override suspend fun remove(placementId: Long): EmptyDataResult<DataError.Network> =
        remoteDataSource.remove(placementId)

    override suspend fun reorder(
        time: LocalTime,
        date: LocalDate,
        orderedIds: List<Long>,
    ): EmptyDataResult<DataError.Network> = remoteDataSource.reorder(time, date, orderedIds)
}

class ProgramsRepositoryImpl(
    private val remoteDataSource: RemoteProgramsDataSource,
) : ProgramsRepository {

    override suspend fun list(): DataResult<List<Program>, DataError.Network> = remoteDataSource.list()

    override suspend fun create(name: String, colorArgb: Int?): DataResult<Program, DataError.Network> =
        remoteDataSource.create(name, colorArgb)

    override suspend fun update(id: Long, name: String?, colorArgb: Int?): EmptyDataResult<DataError.Network> =
        remoteDataSource.update(id, name, colorArgb)

    override suspend fun remove(id: Long): EmptyDataResult<DataError.Network> = remoteDataSource.remove(id)
}

class FinderRepositoryImpl(
    private val remoteDataSource: RemoteFinderDataSource,
) : FinderRepository {

    override suspend fun contractLines(
        clientCode: String,
        kind: PartyKind,
    ): DataResult<List<ContractLine>, DataError.Network> = remoteDataSource.contractLines(clientCode, kind)

    override suspend fun lineSpots(lineId: Long): DataResult<List<ContractLineSpot>, DataError.Network> =
        remoteDataSource.lineSpots(lineId)
}
