package eu.anifantakis.commercials.feature.timetable.domain.data_source

import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
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
 * Network side of the timetable - the only classes that touch the HTTP
 * client. One interface per I/O concern, mirroring the repositories.
 */

/** Read side: the grid's rows + the month grid. */
interface RemoteScheduleDataSource {
    suspend fun getBreaks(
        year: Int,
        month: Int,
        mode: GridViewMode,
    ): DataResult<List<BreakSlotInfo>, DataError.Network>
    suspend fun getMonth(year: Int, month: Int, mode: GridViewMode): DataResult<MonthSchedule, DataError.Network>
    suspend fun getCommercials(
        year: Int,
        month: Int,
        date: LocalDate? = null,
        time: LocalTime? = null,
    ): DataResult<Map<Pair<LocalTime, LocalDate>, List<PlacedCommercial>>, DataError.Network>
}

/** Write side: the 'a'/'r' keys, the detail screen's reorder, break creation. */
interface RemotePlacementsDataSource {
    /** [programId] paints a WHITE cell's break on the first spot; painted breaks ignore it. */
    suspend fun add(
        spotId: Long,
        time: LocalTime,
        date: LocalDate,
        programId: Long? = null,
    ): DataResult<PlacedCommercial, DataError.Network>
    suspend fun remove(placementId: Long): EmptyDataResult<DataError.Network>
    suspend fun reorder(time: LocalTime, date: LocalDate, orderedIds: List<Long>): EmptyDataResult<DataError.Network>
    /** An EMPTY, UNPAINTED break at (time, date) - it holds a grid ROW. */
    suspend fun createBreak(time: LocalTime, date: LocalDate): EmptyDataResult<DataError.Network>
}

/** The programme catalog (Τύποι Προγράμματος): dropdown content + its CRUD. */
interface RemoteProgramsDataSource {
    suspend fun list(): DataResult<List<Program>, DataError.Network>
    suspend fun create(name: String, colorArgb: Int?): DataResult<Program, DataError.Network>
    suspend fun update(id: Long, name: String?, colorArgb: Int?): EmptyDataResult<DataError.Network>
    suspend fun remove(id: Long): EmptyDataResult<DataError.Network>
}

/** The finder console's drill-down: party -> contract lines -> spots. */
interface RemoteFinderDataSource {
    suspend fun contractLines(clientCode: String, kind: PartyKind): DataResult<List<ContractLine>, DataError.Network>
    suspend fun lineSpots(lineId: Long): DataResult<List<ContractLineSpot>, DataError.Network>
}
