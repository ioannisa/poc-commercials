package eu.anifantakis.commercials.feature.timetable.domain.model

import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * ≙ the legacy console's "Προβολή κάθε: 1 Ώρα / Μισή Ώρα / Διάλειμμα" - which
 * rows the grid draws.
 *
 * It is sent to the server, because the row list is not a filter over some
 * catalog the client holds: a break EXISTS only where a spot aired, so the rows
 * are the month's real breaks plus the empty scaffold this view prints, and only
 * the server knows both (see the /api/breaks route).
 */
enum class GridViewMode { CONDENSED, HALF_HOURLY, HOURLY }

/**
 * One ROW of the grid. It is a TIME - there is no break id, because there is no
 * break table on the server: a break is a time something aired at.
 *
 * A row can be EMPTY (an hourly view prints 08:00 whether or not anything airs
 * there), which is why an id could not have survived: an empty row has no break
 * to have one.
 */
data class BreakSlotInfo(
    val time: LocalTime,
    val zone: String,
    val zoneColorArgb: Int,
) {
    val hour: Int get() = time.hour
    val minute: Int get() = time.minute
    val label: String get() = "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

/** One placement (a spot airing at a position inside a cell). */
data class PlacedCommercial(
    val id: Long,
    val position: Int,
    val clientCode: String,
    val clientName: String,
    val message: String,
    val durationSeconds: Int,
    val type: String,
    /** Sales item of the contract line (Break Console Τύπος); null -> show [type]. */
    val salesItem: String? = null,
    val contract: String,
    val isGift: Boolean = false,
    /** Legacy calendar_excluded_docs: aired normally, kept OFF printed reports. */
    val excludeFromReports: Boolean = false,
    val flow: String,
)

/** One (break, date) cell of the month grid with its aggregates. */
data class ScheduleCell(
    /** The break's TIME - its identity. */
    val time: LocalTime,
    val date: LocalDate,
    val spotCount: Int,
    val totalDurationSeconds: Int,
    val zoneColorArgb: Int,
    /** THE BREAK's programme (the break owns it server-side), when it has one. */
    val programName: String? = null,
    val commercials: List<PlacedCommercial>,
)

/**
 * One programme of the station's catalog - the legacy console's "Τύποι
 * Προγράμματος" dropdown. Its colour is DATA (never theme-adapted): the
 * selected programme paints the NEXT break the operator creates, and
 * recoloring a programme repaints every cell whose break carries it.
 */
data class Program(
    val id: Long,
    val name: String,
    /** Packed ARGB; null -> the programme paints nothing (zone colours apply). */
    val colorArgb: Int? = null,
)

/**
 * ≙ the legacy console's "Προβολή Βάσει…" - WHOSE airings the month grid
 * counts. Null = Όλα.
 *
 * It travels to the server, because the grid's cells are AGGREGATES: with no
 * airings on the client there is nothing left to filter here, so the counts
 * must be recomputed inside the same scan that produces them (exactly the
 * CUSTOMER_VIEWER precedent - see the /api/schedule route).
 */
sealed interface ScheduleFilter {
    /** Cells whose BREAK carries the programme (the break owns its paint). */
    data class ByProgram(val programId: Long) : ScheduleFilter

    /**
     * The finder's selected party: a CUSTOMER counts their own spots, a
     * TRADER the spots under contracts they pay - the triangular split.
     */
    data class ByParty(val code: String, val kind: PartyKind) : ScheduleFilter

    /**
     * «Συμβολαίου»: the selected line's WHOLE contract - every line of that
     * ONE deal. Legacy doc numbers repeat per customer (two KRIVEK gift docs
     * are both «18») but those are DIFFERENT deals and stay out; the finder's
     * period column is what tells them apart when selecting.
     */
    data class ByContract(val lineId: Long) : ScheduleFilter

    /** One spot (the finder's armed message - «Μηνύματος»). */
    data class BySpot(val spotId: Long) : ScheduleFilter
}

/**
 * A month's grid: its ROWS and its CELLS, from ONE call.
 *
 * The rows are the DISTINCT times of the cells plus the view's empty scaffold, so
 * the server derives them from the same scan - asking for them separately meant
 * two round trips and two scans of the same month.
 */
data class MonthSchedule(
    val year: Int,
    val month: Int,
    val rows: List<BreakSlotInfo>,
    val cells: List<ScheduleCell>,
)
