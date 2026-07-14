package eu.anifantakis.commercials.feature.timetable.domain

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
import eu.anifantakis.commercials.feature.timetable.domain.model.MonthSchedule

/** Read side of the scheduler grid (station-scoped by the session). */
interface ScheduleRepository {
    /**
     * The grid's ROWS for a month, in [mode]. Both arguments are load-bearing:
     * the month decides which breaks exist at all (a break is a time a spot
     * aired at), and the mode decides how much empty scaffold is drawn around
     * them.
     */
    suspend fun getBreaks(
        year: Int,
        month: Int,
        mode: GridViewMode,
    ): DataResult<List<BreakSlotInfo>, DataError.Network>

    suspend fun getMonth(year: Int, month: Int): DataResult<MonthSchedule, DataError.Network>
}
