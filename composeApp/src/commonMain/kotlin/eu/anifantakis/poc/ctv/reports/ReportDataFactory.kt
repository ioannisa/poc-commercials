package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.grids.BreakSlot
import eu.anifantakis.poc.ctv.grids.SchedulerCellData
import eu.anifantakis.poc.ctv.grids.SchedulerKey
import eu.anifantakis.poc.ctv.reports.models.ProgramFlowItem
import eu.anifantakis.poc.ctv.reports.models.ProgramFlowReportData
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * Factory functions to create report data from scheduler data
 */
object ReportDataFactory {

    /**
     * Creates Program Flow report data from scheduler data
     *
     * @param date The date for the report
     * @param breaks List of all break slots
     * @param cellData Map of scheduler cell data
     * @param emptyTimeSeconds Total empty time in seconds
     * @return ProgramFlowReportData ready for report generation
     */
    fun createProgramFlowData(
        date: LocalDate,
        breaks: List<BreakSlot>,
        cellData: Map<SchedulerKey, SchedulerCellData>,
        emptyTimeSeconds: Int = 0
    ): ProgramFlowReportData {
        val items = mutableListOf<ProgramFlowItem>()

        // Group commercials by time slot
        val breaksWithData = breaks.filter { breakSlot ->
            val key = SchedulerKey(breakSlot.id, date)
            (cellData[key]?.spotCount ?: 0) > 0
        }

        breaksWithData.forEach { breakSlot ->
            val key = SchedulerKey(breakSlot.id, date)
            val data = cellData[key] ?: SchedulerCellData()
            val commercials = data.commercials

            commercials.forEachIndexed { index, commercial ->
                items.add(
                    ProgramFlowItem(
                        timeSlot = formatTime(breakSlot.time.hour, breakSlot.time.minute),
                        timeSlotTime = breakSlot.time,
                        message = commercial.message,
                        duration = formatDuration(commercial.durationSeconds),
                        durationSeconds = commercial.durationSeconds,
                        program = commercial.type,
                        notes = if (commercial.flow == "ΡΟΗ") "ΕΟΡΤΑΣΤΙΚΟ ΠΡΟΓΡΑΜΜΑ" else commercial.flow,
                        firstInGroup = index == 0,
                        lastInGroup = index == commercials.size - 1,
                        groupTotalDuration = formatDuration(data.totalDurationSeconds),
                        groupSpotCount = data.spotCount
                    )
                )
            }
        }

        return ProgramFlowReportData(
            title = "ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ",
            generatedAt = currentTimeMillis(),
            date = date,
            dateFormatted = formatGreekDate(date),
            emptyTimeFormatted = "Κενός Χρόνος: ${formatDuration(emptyTimeSeconds)}",
            items = items
        )
    }

    /**
     * Format time as HH:MM
     */
    fun formatTime(hour: Int, minute: Int): String {
        return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    /**
     * Format duration from seconds to MM:SS
     */
    fun formatDuration(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }

    /**
     * Format date in Greek format
     */
    private fun formatGreekDate(date: LocalDate): String {
        val dayOfWeek = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "Δευτέρα"
            DayOfWeek.TUESDAY -> "Τρίτη"
            DayOfWeek.WEDNESDAY -> "Τετάρτη"
            DayOfWeek.THURSDAY -> "Πέμπτη"
            DayOfWeek.FRIDAY -> "Παρασκευή"
            DayOfWeek.SATURDAY -> "Σάββατο"
            DayOfWeek.SUNDAY -> "Κυριακή"
            else -> ""
        }
        return "$dayOfWeek - ${date.dayOfMonth.toString().padStart(2, '0')}/${date.monthNumber.toString().padStart(2, '0')}/${date.year}"
    }
}

/**
 * Platform-specific function to get current time in milliseconds
 */
expect fun currentTimeMillis(): Long
