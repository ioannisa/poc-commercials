package eu.anifantakis.commercials.scheduleemail

import eu.anifantakis.commercials.server.scheduler.CellRow
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.scheduler.StationDb
import java.time.LocalDate
import java.time.LocalTime

/**
 * The NARROW read port the schedule-email assembly needs — the three reads, and
 * nothing else.
 *
 * DIP: the assembler is an ORGANIZER of reads, never an implementor of I/O, so it
 * must not import the concrete JDBC [StationDb] (our "DAO"). Depending on this
 * interface instead makes the assembly — the triangular CUS/TRA logic, the
 * per-spot sections, the month grid — trivially fakeable in a unit test, which
 * the concrete class made impossible.
 *
 * [StationDb] satisfies it through [asScheduleEmailSource]; the dependency
 * direction stays `schedule-email -> persistence`.
 */
interface ScheduleEmailSource {
    fun customerByCode(code: String): StationDb.CustomerContact?

    /**
     * The month's grid. There is no companion `loadBreaks` any more: the cell
     * keys ARE the breaks (a break is a time), so the assembler reads the ones a
     * spot aired in straight off them instead of fetching a catalog to filter.
     */
    fun loadMonth(year: Int, month: Int): Pair<List<CellRow>, Map<Pair<LocalTime, LocalDate>, List<CommercialRow>>>
}

/** Adapts the station database to the assembler's read port. */
fun StationDb.asScheduleEmailSource(): ScheduleEmailSource {
    val db = this
    return object : ScheduleEmailSource {
        override fun customerByCode(code: String): StationDb.CustomerContact? = db.customerByCode(code)
        override fun loadMonth(
            year: Int,
            month: Int,
        ): Pair<List<CellRow>, Map<Pair<LocalTime, LocalDate>, List<CommercialRow>>> = db.loadMonth(year, month)
    }
}
