package eu.anifantakis.commercials.feature.timetable.data.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class BreakSlotDto(
    /** "HH:mm" - the row's identity. There is no break id; a break is its time. */
    val time: String,
    val zone: String,
    val zoneColorArgb: Int,
)

@Serializable
internal data class CommercialDto(
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
    val excludeFromReports: Boolean = false,
    val flow: String,
)

/**
 * One box of the grid: AGGREGATES ONLY. It carries no airings - the box draws a
 * count and a duration, and shipping 13,009 airings to paint 1,295 boxes cost
 * 7.79 MB. They arrive from [CommercialsDto], on demand.
 */
@Serializable
internal data class CellDto(
    /** "HH:mm" - the break this cell belongs to. */
    val time: String,
    val date: String, // ISO yyyy-MM-dd
    val spotCount: Int,
    val totalDurationSeconds: Int,
    val zoneColorArgb: Int,
    /** THE BREAK's programme (the break owns it server-side), when it has one. */
    val programName: String? = null,
)

/** The whole grid in ONE response: its ROWS and its CELLS (one scan, one round trip). */
@Serializable
internal data class ScheduleDto(
    val year: Int,
    val month: Int,
    val rows: List<BreakSlotDto>,
    val cells: List<CellDto>,
)

/** The airings of one cell - only ever fetched for a break that is opened or printed. */
@Serializable
internal data class CellCommercialsDto(
    val time: String,
    val date: String,
    val commercials: List<CommercialDto>,
)

@Serializable
internal data class CommercialsDto(
    val year: Int,
    val month: Int,
    val cells: List<CellCommercialsDto>,
)

@Serializable
internal data class ContractLineDto(
    val lineId: Long,
    val contractNumber: String,
    val isGift: Boolean,
    val lineNo: Int,
    val desiredQty: Int,
    val spotCount: Int,
    val placements: Int,
    val totalSeconds: Long,
    val entryDate: String? = null,
)

@Serializable
internal data class FinderSpotDto(
    val spotId: Long,
    val description: String,
    val durationSeconds: Int,
    val placements: Int,
    val totalSeconds: Long = 0,
)

/**
 * [time] is "HH:mm" and need NOT already have a break - putting a spot there
 * creates it. [programId] (the operator's selected Τύπος Προγράμματος) matters
 * only for a WHITE cell (no break, or an unpainted one): the first spot paints
 * the break with it. A painted break ignores [programId]: the spot inherits
 * the BREAK's programme, never the selection.
 */
@Serializable
internal data class AddPlacementRequest(
    val spotId: Long,
    val time: String,
    val date: String,
    val programId: Long? = null,
)

@Serializable
internal data class ReorderPlacementsRequest(val time: String, val date: String, val orderedIds: List<Long>)

/** One programme of the station's catalog (the "Τύποι Προγράμματος" dropdown). */
@Serializable
internal data class ProgramDto(
    val id: Long,
    val name: String,
    /** Packed ARGB; null -> the programme paints nothing (zone colours apply). */
    val colorArgb: Int? = null,
)

@Serializable
internal data class CreateProgramRequest(val name: String, val colorArgb: Int? = null)

/** Nulls keep the current value - send only what changed. */
@Serializable
internal data class UpdateProgramRequest(val name: String? = null, val colorArgb: Int? = null)

/** An EMPTY, UNPAINTED break at (time, date) - it holds a grid ROW. */
@Serializable
internal data class CreateBreakRequest(val time: String, val date: String)
