package eu.anifantakis.commercials.feature.timetable.data

import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.feature.timetable.domain.FinderRepository
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
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
 * Organizers only: every wire call lives in the Remote*DataSource impls
 * (data_source/); these compose them and hold whatever policy shows up
 * later (caching, merging, retries).
 */

class ScheduleRepositoryImpl(
    private val remoteDataSource: RemoteScheduleDataSource,
) : ScheduleRepository {

    override suspend fun getBreaks(): DataResult<List<BreakSlotInfo>, DataError.Network> =
        remoteDataSource.getBreaks()

    override suspend fun getMonth(year: Int, month: Int): DataResult<MonthSchedule, DataError.Network> =
        remoteDataSource.getMonth(year, month)
}

class PlacementsRepositoryImpl(
    private val remoteDataSource: RemotePlacementsDataSource,
) : PlacementsRepository {

    override suspend fun add(
        spotId: Long,
        breakId: Long,
        date: LocalDate,
    ): DataResult<PlacedCommercial, DataError.Network> = remoteDataSource.add(spotId, breakId, date)

    override suspend fun remove(placementId: Long): EmptyDataResult<DataError.Network> =
        remoteDataSource.remove(placementId)

    override suspend fun reorder(
        breakId: Long,
        date: LocalDate,
        orderedIds: List<Long>,
    ): EmptyDataResult<DataError.Network> = remoteDataSource.reorder(breakId, date, orderedIds)
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
