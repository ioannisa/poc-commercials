package eu.anifantakis.commercials.feature.timetable.domain.model

import kotlinx.datetime.LocalDate

/** One break slot of the station's airtime grid. */
data class BreakSlotInfo(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String,
    val zone: String,
    val zoneColorArgb: Int,
)

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
    val breakId: Long,
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
