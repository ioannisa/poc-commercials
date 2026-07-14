package eu.anifantakis.commercials.feature.timetable.domain

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
import eu.anifantakis.commercials.feature.timetable.domain.model.MonthSchedule
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

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

    /**
     * The month's grid: one aggregate per cell (count + duration + colour). It
     * carries NO airings - see [getCommercials].
     */
    suspend fun getMonth(year: Int, month: Int): DataResult<MonthSchedule, DataError.Network>

    /**
     * The airings, for a slice: the whole month, one day ([date]), one break
     * across the month ([time]), or a single cell (both).
     *
     * Separate from [getMonth] on purpose. The grid never reads an airing, and
     * shipping the month's 13,009 of them to draw 1,295 boxes cost 7.79 MB. Only
     * opening a break or printing actually needs them, and each of those wants a
     * SLICE - so each asks for exactly its own.
     */
    suspend fun getCommercials(
        year: Int,
        month: Int,
        date: LocalDate? = null,
        time: LocalTime? = null,
    ): DataResult<Map<Pair<LocalTime, LocalDate>, List<PlacedCommercial>>, DataError.Network>
}
