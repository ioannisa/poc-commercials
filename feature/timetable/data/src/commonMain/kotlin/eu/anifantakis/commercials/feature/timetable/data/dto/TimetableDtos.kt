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

@Serializable
internal data class CellDto(
    /** "HH:mm" - the break this cell belongs to. */
    val time: String,
    val date: String, // ISO yyyy-MM-dd
    val spotCount: Int,
    val totalDurationSeconds: Int,
    val zoneColorArgb: Int,
    /** The programme airing at this slot (first placement's), when it has one. */
    val programName: String? = null,
    val commercials: List<CommercialDto>,
)

@Serializable
internal data class ScheduleDto(
    val year: Int,
    val month: Int,
    val cells: List<CellDto>,
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

/** [time] is "HH:mm" and need NOT already have a break - putting a spot there creates it. */
@Serializable
internal data class AddPlacementRequest(val spotId: Long, val time: String, val date: String)

@Serializable
internal data class ReorderPlacementsRequest(val time: String, val date: String, val orderedIds: List<Long>)
