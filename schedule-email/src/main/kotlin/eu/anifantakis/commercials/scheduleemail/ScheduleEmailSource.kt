package eu.anifantakis.commercials.scheduleemail

import eu.anifantakis.commercials.server.scheduler.BreakSlotRow
import eu.anifantakis.commercials.server.scheduler.CellRow
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.scheduler.StationDb
import java.time.LocalDate

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
    fun loadBreaks(): List<BreakSlotRow>
    fun loadMonth(year: Int, month: Int): Pair<List<CellRow>, Map<Pair<Long, LocalDate>, List<CommercialRow>>>
}

/** Adapts the station database to the assembler's read port. */
fun StationDb.asScheduleEmailSource(): ScheduleEmailSource {
    val db = this
    return object : ScheduleEmailSource {
        override fun customerByCode(code: String): StationDb.CustomerContact? = db.customerByCode(code)
        override fun loadBreaks(): List<BreakSlotRow> = db.loadBreaks()
        override fun loadMonth(
            year: Int,
            month: Int,
        ): Pair<List<CellRow>, Map<Pair<Long, LocalDate>, List<CommercialRow>>> = db.loadMonth(year, month)
    }
}
