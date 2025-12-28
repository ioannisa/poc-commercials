package eu.anifantakis.poc.ctv.reports.models

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Base interface for all report data
 */
interface ReportData {
    val title: String
    val generatedAt: Long // epoch milliseconds
}

/**
 * Report configuration settings
 */
data class ReportConfig(
    val logoPath: String? = null,
    val showGeneratedTimestamp: Boolean = true,
    val paperSize: PaperSize = PaperSize.A4,
    val orientation: Orientation = Orientation.PORTRAIT
)

enum class PaperSize {
    A4, LETTER, LEGAL
}

enum class Orientation {
    PORTRAIT, LANDSCAPE
}

/**
 * Result of a report operation
 */
sealed class ReportResult {
    data class Success(val message: String, val filePath: String? = null) : ReportResult()
    data class Error(val message: String, val exception: Throwable? = null) : ReportResult()
    data object Cancelled : ReportResult()
}

// ============================================================================
// PROGRAM FLOW REPORT MODELS
// ============================================================================

/**
 * Data for the Program Flow report (ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ)
 * This is passed to JasperReports as the main data source
 */
data class ProgramFlowReportData(
    override val title: String = "ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ",
    override val generatedAt: Long,
    val date: LocalDate,
    val dateFormatted: String,
    val emptyTimeFormatted: String,
    val items: List<ProgramFlowItem>
) : ReportData

/**
 * A single item in the Program Flow report
 * This is a flattened view suitable for JasperReports data source
 */
data class ProgramFlowItem(
    // Time slot info
    val timeSlot: String,        // e.g., "08:00"
    val timeSlotTime: LocalTime,

    // Commercial info
    val message: String,
    val duration: String,        // formatted duration e.g., "00:30"
    val durationSeconds: Int,
    val program: String,         // type
    val notes: String,           // flow/notes

    // Group tracking (for subtotals)
    val firstInGroup: Boolean = false,
    val lastInGroup: Boolean = false,
    val groupTotalDuration: String = "",
    val groupSpotCount: Int = 0
)

/**
 * Summary data for a time slot group (used for subtotals)
 */
data class TimeSlotSummary(
    val timeSlot: String,
    val totalDurationSeconds: Int,
    val totalDurationFormatted: String,
    val spotCount: Int
)
