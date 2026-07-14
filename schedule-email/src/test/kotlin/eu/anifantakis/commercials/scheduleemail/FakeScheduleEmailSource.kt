package eu.anifantakis.commercials.scheduleemail

import eu.anifantakis.commercials.server.scheduler.CellRow
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.scheduler.StationDb
import java.time.LocalDate
import java.time.LocalTime

/**
 * In-memory [ScheduleEmailSource]. A fake, not a mock (mocking libs are JVM-only
 * and hide real bugs); the port exists precisely so the assembly can be driven
 * from canned rows instead of a live MySQL.
 *
 * There is no canned break catalog any more: a break IS a time, so the cell keys
 * are the only place breaks come from.
 */
internal class FakeScheduleEmailSource(
    private val customers: Map<String, StationDb.CustomerContact> = emptyMap(),
    private val cells: List<CellRow> = emptyList(),
    private val commercialsByKey: Map<Pair<LocalTime, LocalDate>, List<CommercialRow>> = emptyMap(),
) : ScheduleEmailSource {
    override fun customerByCode(code: String): StationDb.CustomerContact? = customers[code]
    override fun loadMonth(
        year: Int,
        month: Int,
    ): Pair<List<CellRow>, Map<Pair<LocalTime, LocalDate>, List<CommercialRow>>> = cells to commercialsByKey
}
