package eu.anifantakis.poc.ctv.server.reports

import kotlinx.serialization.Serializable

/**
 * Request model for generating a Program Flow report
 */
@Serializable
data class ProgramFlowReportRequest(
    val title: String = "ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ",
    val date: String,  // ISO date format: "2025-12-28"
    val emptyTimeIndicator: String = "Κενός Χρόνος: 00:00",
    val timeSlotGroups: List<TimeSlotGroupDto>,
    val fileName: String? = null,
    val logoPath: String? = null
)

@Serializable
data class TimeSlotGroupDto(
    val timeLabel: String,
    val items: List<ProgramFlowItemDto>,
    val totalDuration: String,
    val spotCount: Int
)

@Serializable
data class ProgramFlowItemDto(
    val message: String,
    val time: String,
    val duration: String,
    val program: String,
    val notes: String
)

/**
 * Generic report configuration
 */
@Serializable
data class ReportConfigDto(
    val paperSize: String = "A4",
    val orientation: String = "PORTRAIT",
    val logoPath: String? = null,
    val showGeneratedTimestamp: Boolean = true
)
