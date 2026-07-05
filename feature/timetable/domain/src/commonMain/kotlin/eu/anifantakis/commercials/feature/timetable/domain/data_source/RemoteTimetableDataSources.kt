package eu.anifantakis.commercials.feature.timetable.domain.data_source

import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.domain.model.MonthSchedule
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import kotlinx.datetime.LocalDate

/*
 * Network side of the timetable - the only classes that touch the HTTP
 * client. One interface per I/O concern, mirroring the repositories.
 */

/** Read side: breaks + the month grid. */
interface RemoteScheduleDataSource {
    suspend fun getBreaks(): DataResult<List<BreakSlotInfo>, DataError.Network>
    suspend fun getMonth(year: Int, month: Int): DataResult<MonthSchedule, DataError.Network>
}

/** Write side: the 'a'/'r' keys and the detail screen's reorder. */
interface RemotePlacementsDataSource {
    suspend fun add(spotId: Long, breakId: Long, date: LocalDate): DataResult<PlacedCommercial, DataError.Network>
    suspend fun remove(placementId: Long): EmptyDataResult<DataError.Network>
    suspend fun reorder(breakId: Long, date: LocalDate, orderedIds: List<Long>): EmptyDataResult<DataError.Network>
}

/** The finder console's drill-down: party -> contract lines -> spots. */
interface RemoteFinderDataSource {
    suspend fun contractLines(clientCode: String, kind: PartyKind): DataResult<List<ContractLine>, DataError.Network>
    suspend fun lineSpots(lineId: Long): DataResult<List<ContractLineSpot>, DataError.Network>
}
