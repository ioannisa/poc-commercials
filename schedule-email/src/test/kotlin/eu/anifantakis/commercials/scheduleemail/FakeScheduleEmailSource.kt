package eu.anifantakis.commercials.scheduleemail

import eu.anifantakis.commercials.server.scheduler.BreakSlotRow
import eu.anifantakis.commercials.server.scheduler.CellRow
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.scheduler.StationDb
import java.time.LocalDate

/**
 * In-memory [ScheduleEmailSource]. A fake, not a mock (mocking libs are JVM-only
 * and hide real bugs); the port exists precisely so the assembly can be driven
 * from canned rows instead of a live MySQL.
 */
internal class FakeScheduleEmailSource(
    private val customers: Map<String, StationDb.CustomerContact> = emptyMap(),
    private val breaks: List<BreakSlotRow> = emptyList(),
    private val cells: List<CellRow> = emptyList(),
    private val commercialsByKey: Map<Pair<Long, LocalDate>, List<CommercialRow>> = emptyMap(),
) : ScheduleEmailSource {
    override fun customerByCode(code: String): StationDb.CustomerContact? = customers[code]
    override fun loadBreaks(): List<BreakSlotRow> = breaks
    override fun loadMonth(
        year: Int,
        month: Int,
    ): Pair<List<CellRow>, Map<Pair<Long, LocalDate>, List<CommercialRow>>> = cells to commercialsByKey
}
