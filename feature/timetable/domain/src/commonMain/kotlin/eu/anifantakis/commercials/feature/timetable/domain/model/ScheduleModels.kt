package eu.anifantakis.commercials.feature.timetable.domain.model

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
    /** The programme airing at this slot (first placement's), when it has one. */
    val programName: String? = null,
    val commercials: List<PlacedCommercial>,
)

data class MonthSchedule(
    val year: Int,
    val month: Int,
    val cells: List<ScheduleCell>,
)
