package eu.anifantakis.commercials.scheduleemail

import eu.anifantakis.commercials.mailer.EmailCell
import eu.anifantakis.commercials.mailer.EmailGridRow
import eu.anifantakis.commercials.mailer.ProgramTotal
import eu.anifantakis.commercials.mailer.ScheduleEmailData
import eu.anifantakis.commercials.mailer.SmtpSettings
import eu.anifantakis.commercials.mailer.SpotSection
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.scheduler.formatHhMm
import eu.anifantakis.commercials.server.stations.SmtpConfig
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * The single home of the customer schedule-email assembly. Builds
 * [ScheduleEmailData] from station-DB rows: ONE section per spot the party ran
 * that month (optionally restricted to `spotIds`), each a grid of just that
 * spot's placements with its own per-programme breakdown. In trader mode a spot
 * may belong to a different end customer (the triangular case).
 *
 * Shared by the Ktor server's `/api/email/schedule` route and the MCP
 * `send_schedule_email` tool. It is PURE: it never seeds demo months - the REST
 * route calls `StationDb.ensureMonthSeeded` itself before assembling; the MCP
 * tool deliberately does not (a real email must not fabricate demo placements).
 *
 * It ORGANIZES reads through the [ScheduleEmailSource] port, never touching the
 * concrete JDBC `StationDb` - which is what makes [assemble] unit-testable.
 */
object ScheduleEmailAssembler {

    fun assemble(
        source: ScheduleEmailSource,
        stationName: String,
        year: Int,
        month: Int,
        clientCode: String,
        byTrader: Boolean,
        spotIds: Set<Long>,
        personalMessage: String?,
    ): ScheduleEmailData? {
        val customer = source.customerByCode(clientCode) ?: return null

        val (cells, commercialsByKey) = source.loadMonth(year, month)
        val colorByKey = cells.associate { (it.time to it.date) to it.zoneColorArgb }
        val days = YearMonth.of(year, month).lengthOfMonth()

        fun isMine(row: CommercialRow): Boolean =
            if (byTrader) row.payerCode == clientCode else row.clientCode == clientCode

        fun rowsFor(spotId: Long): Map<Pair<LocalTime, LocalDate>, List<CommercialRow>> =
            commercialsByKey.mapValues { (_, list) ->
                list.filter { isMine(it) && it.spotId == spotId }
            }.filterValues { it.isNotEmpty() }

        val chosenSpots = commercialsByKey.values.flatten()
            .filter { isMine(it) }
            .let { mine ->
                if (mine.isEmpty()) return null
                val wanted = if (spotIds.isEmpty()) mine.map { it.spotId }.toSet() else spotIds
                mine.filter { it.spotId in wanted }
                    .groupBy { it.spotId }
                    .entries.sortedByDescending { it.value.size }
                    .map { it.key to spotLabel(it.value.first(), byTrader, clientCode) }
            }
        if (chosenSpots.isEmpty()) return null

        val sections = chosenSpots.map { (spotId, description) ->
            val mine = rowsFor(spotId)
            // The breaks this spot actually aired in - which its own cells already
            // name, since a break IS the time on the key. (This used to read the
            // station's whole break catalog and filter it down to the same set.)
            val usedBreaks = mine.keys.map { it.first }.distinct().sorted()
            val rows = usedBreaks.map { time ->
                EmailGridRow(
                    label = formatHhMm(time),
                    cells = (1..days).map { day ->
                        val key = time to LocalDate.of(year, month, day)
                        mine[key]?.let { EmailCell(count = it.size, colorArgb = colorByKey[key]) }
                    }
                )
            }
            val totals = mine.values.flatten()
                .groupBy { it.programName ?: it.type.ifBlank { "Λοιπά" } }
                .map { (name, rs) -> ProgramTotal(name, rs.firstNotNullOfOrNull { it.programColorArgb }, rs.size) }
            SpotSection(description = description, rows = rows, programTotals = totals)
        }

        return ScheduleEmailData(
            stationName = stationName,
            customerName = customer.name,
            year = year,
            month = month,
            personalMessage = personalMessage,
            spots = sections,
        )
    }

    /**
     * Label a spot with its end customer when a trader's email covers others'
     * spots (the triangular case). Also used by the `/spots` list endpoint.
     */
    fun spotLabel(row: CommercialRow, byTrader: Boolean, partyCode: String): String =
        if (byTrader && row.clientCode != partyCode) "${row.message} — ${row.clientName}" else row.message

    fun SmtpConfig.toSettings(): SmtpSettings =
        SmtpSettings(host = host, port = port, username = username, password = password, from = from, startTls = startTls)

    val greekMonths: List<String> = listOf(
        "Ιανουάριος", "Φεβρουάριος", "Μάρτιος", "Απρίλιος", "Μάιος", "Ιούνιος",
        "Ιούλιος", "Αύγουστος", "Σεπτέμβριος", "Οκτώβριος", "Νοέμβριος", "Δεκέμβριος",
    )
}
