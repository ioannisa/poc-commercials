package eu.anifantakis.commercials.feature.timetable.domain

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.domain.model.MonthSchedule

/** Read side of the scheduler grid (station-scoped by the session). */
interface ScheduleRepository {
    suspend fun getBreaks(): DataResult<List<BreakSlotInfo>, DataError.Network>
    suspend fun getMonth(year: Int, month: Int): DataResult<MonthSchedule, DataError.Network>
}
