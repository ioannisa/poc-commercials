package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.core.presentation.grids.BreakSlot
import eu.anifantakis.commercials.core.presentation.grids.CommercialItem
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.reports.model.ProgramFlow
import eu.anifantakis.commercials.reports.models.ProgramFlowItem
import eu.anifantakis.commercials.reports.models.ProgramFlowReportData
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

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
            // calendar_excluded_docs contracts air but stay off printed reports
            val commercials = data.commercials.filter { !it.excludeFromReports }
            if (commercials.isEmpty()) return@forEach

            commercials.forEachIndexed { index, commercial ->
                items.add(
                    ProgramFlowItem(
                        timeSlot = formatTime(breakSlot.time.hour, breakSlot.time.minute),
                        timeSlotTime = breakSlot.time,
                        message = commercial.message,
                        duration = formatDuration(commercial.durationSeconds),
                        durationSeconds = commercial.durationSeconds,
                        program = commercial.type,
                        notes = ProgramFlow.notes(commercial.flow),
                        firstInGroup = index == 0,
                        lastInGroup = index == commercials.size - 1,
                        groupTotalDuration = formatDuration(data.totalDurationSeconds),
                        groupSpotCount = data.spotCount
                    )
                )
            }
        }

        return ProgramFlowReportData(
            title = ProgramFlow.TITLE,
            generatedAt = currentTimeMillis(),
            date = date,
            dateFormatted = ProgramFlow.formatGreekDate(date),
            emptyTimeFormatted = ProgramFlow.emptyTime(emptyTimeSeconds),
            items = items
        )
    }

    /**
     * Creates Program Flow report data for every day of a month that has
     * spots - one entry per day, in date order. Feed the mapped payloads to
     * ReportService as a batch to get the whole month as one document.
     */
    fun createMonthProgramFlowData(
        year: Int,
        month: Int,
        breaks: List<BreakSlot>,
        cellData: Map<SchedulerKey, SchedulerCellData>
    ): List<ProgramFlowReportData> {
        val firstDay = LocalDate(year, month, 1)
        val daysInMonth = firstDay.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
        return (1..daysInMonth)
            .map { day -> createProgramFlowData(LocalDate(year, month, day), breaks, cellData) }
            .filter { it.items.isNotEmpty() }
    }

    /**
     * Creates Program Flow report data for a single break of a single day,
     * from the break's commercials directly (used by the break popup and the
     * break details screen, which hold the commercial list rather than the
     * whole scheduler map).
     */
    fun createBreakProgramFlowData(
        date: LocalDate,
        breakTimeLabel: String,
        commercials: List<CommercialItem>
    ): ProgramFlowReportData {
        // calendar_excluded_docs contracts air but stay off printed reports
        @Suppress("NAME_SHADOWING")
        val commercials = commercials.filter { !it.excludeFromReports }
        val totalDurationSeconds = commercials.sumOf { it.durationSeconds }
        val time = parseTimeLabel(breakTimeLabel)

        val items = commercials.mapIndexed { index, commercial ->
            ProgramFlowItem(
                timeSlot = breakTimeLabel,
                timeSlotTime = time,
                message = commercial.message,
                duration = formatDuration(commercial.durationSeconds),
                durationSeconds = commercial.durationSeconds,
                program = commercial.type,
                notes = ProgramFlow.notes(commercial.flow),
                firstInGroup = index == 0,
                lastInGroup = index == commercials.lastIndex,
                groupTotalDuration = formatDuration(totalDurationSeconds),
                groupSpotCount = commercials.size
            )
        }

        return ProgramFlowReportData(
            title = ProgramFlow.TITLE,
            generatedAt = currentTimeMillis(),
            date = date,
            dateFormatted = ProgramFlow.formatGreekDate(date),
            emptyTimeFormatted = ProgramFlow.emptyTime(0),
            items = items
        )
    }

    private fun parseTimeLabel(label: String): LocalTime {
        val parts = label.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return LocalTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    }

    /**
     * Format time as HH:MM
     */
    fun formatTime(hour: Int, minute: Int): String {
        return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    /**
     * Format duration from seconds to MM:SS. Delegates to the shared report
     * contract so the client and the backend assembler format identically.
     */
    fun formatDuration(totalSeconds: Int): String = ProgramFlow.formatDuration(totalSeconds)
}

/**
 * Platform-specific function to get current time in milliseconds
 */
expect fun currentTimeMillis(): Long
