package eu.anifantakis.commercials.feature.timetable.domain.model

/**
 * A contract line ("product") in the finder console - ERP product identity
 * is pending migration, so it is presented by contract number + line no
 * with computed stats.
 */
data class ContractLine(
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

/** One spot (creative) of a contract line, with consumption stats. */
data class ContractLineSpot(
    val spotId: Long,
    val description: String,
    val durationSeconds: Int,
    val placements: Int,
    val totalSeconds: Long = 0,
)
