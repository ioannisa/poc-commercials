package eu.anifantakis.commercials.feature.timetable.data.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class BreakSlotDto(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String,
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
    val contract: String,
    val excludeFromReports: Boolean = false,
    val flow: String,
)

@Serializable
internal data class CellDto(
    val breakId: Long,
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

@Serializable
internal data class AddPlacementRequest(val spotId: Long, val breakId: Long, val date: String)

@Serializable
internal data class ReorderPlacementsRequest(val breakId: Long, val date: String, val orderedIds: List<Long>)
