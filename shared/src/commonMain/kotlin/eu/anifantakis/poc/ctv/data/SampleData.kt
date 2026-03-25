package eu.anifantakis.poc.ctv.data

import androidx.compose.ui.graphics.Color
import eu.anifantakis.poc.ctv.grids.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.*

/**
 * Sample data generator for the POC
 */
object SampleData {

    // Zone colors matching the screenshot
    private val zonePink = Color(0xFFFF69B4)      // Hot Pink for prime
    private val zoneBlue = Color(0xFF87CEEB)      // Light Blue for standard
    private val zoneGreen = Color(0xFF90EE90)     // Light Green for special
    private val zoneOrange = Color(0xFFFFE4B5)    // Moccasin/Orange for weekend highlights
    private val zoneYellow = Color(0xFFFFFF99)    // Light Yellow

    /**
     * Generate sample breaks for a day (time slots)
     */
    fun generateBreaks(): List<BreakSlot> {
        val breaks = mutableListOf<BreakSlot>()
        var id = 1L

        // Generate breaks from 00:00 to 23:45 in 15-minute increments
        for (hour in 0..23) {
            for (minute in listOf(0, 15, 30, 45)) {
                val zone = when {
                    hour in 20..23 -> BreakZone.PRIME      // Prime time evening
                    hour in 10..14 -> BreakZone.STANDARD   // Daytime standard
                    hour in 18..19 -> BreakZone.SPECIAL    // Early evening special
                    else -> BreakZone.DEFAULT
                }
                val zoneColor = when (zone) {
                    BreakZone.PRIME -> zonePink
                    BreakZone.STANDARD -> zoneBlue
                    BreakZone.SPECIAL -> zoneGreen
                    BreakZone.DEFAULT -> Color.White
                }

                breaks.add(
                    BreakSlot(
                        id = id++,
                        time = LocalTime(hour, minute),
                        label = formatTime(hour, minute),
                        zone = zone,
                        zoneColor = zoneColor
                    )
                )
            }
        }
        return breaks
    }

    /**
     * Generate sample cell data for the scheduler grid
     * Returns a map of (breakId, date) -> SchedulerCellData
     */
    fun generateCellData(
        breaks: List<BreakSlot>,
        year: Int,
        month: Int
    ): Map<SchedulerKey, SchedulerCellData> {
        val cellData = mutableMapOf<SchedulerKey, SchedulerCellData>()

        val daysInMonth = when {
            month == 2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            month in listOf(4, 6, 9, 11) -> 30
            else -> 31
        }

        // Random number generator with seed for reproducible results
        val random = kotlin.random.Random(year * 100 + month)

        for (day in 1..daysInMonth) {
            val date = LocalDate(year, month, day)
            val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

            for (breakSlot in breaks) {
                // Skip some cells to create sparse data (like the screenshot)
                if (random.nextFloat() > 0.35f) continue

                // Generate 1-15 spots
                val spotCount = random.nextInt(1, 16)

                // Generate duration (20-60 seconds per spot average)
                val avgDuration = random.nextInt(20, 60)
                val totalDuration = spotCount * avgDuration

                // Determine zone color based on various factors
                val cellZoneColor = when {
                    breakSlot.zone == BreakZone.PRIME -> zonePink
                    breakSlot.zone == BreakZone.SPECIAL -> zoneGreen
                    isWeekend -> zoneOrange
                    spotCount > 10 -> zoneYellow
                    else -> Color.White
                }

                // Generate sample commercials for this cell
                val commercials = generateCommercialsForCell(spotCount, random)

                cellData[SchedulerKey(breakSlot.id, date)] = SchedulerCellData(
                    spotCount = spotCount,
                    totalDurationSeconds = totalDuration,
                    zoneColor = cellZoneColor,
                    commercials = commercials
                )
            }
        }

        return cellData
    }

    /**
     * Generate sample commercial items for a cell
     */
    private fun generateCommercialsForCell(count: Int, random: kotlin.random.Random): ImmutableList<CommercialItem> {
        val clients = listOf(
            "30002310" to "ΚΟΙΝΩΝΙΚΑ ΠΕΛΑΤΕΣ ΔΙΑΦΟΡΟΙ",
            "30001604" to "ΥΙΟΙ Κ. ΠΑΤΣΟΥΡΑΚΗ Ο.Ε",
            "30002918" to "ΑΝΤΟΝΑΚΑΚΗ ΑΙΚΑΤ & ΚΟΝ. Ο.Ε",
            "30003875" to "NOVA ΑΠΟΛΥΜΑΝΤΙΚΗ Ε.Ε",
            "30004521" to "ΚΡΗΤΗ ΞΕΝΟΔΟΧΕΙΑ Α.Ε",
            "30005123" to "SUPER MARKET ΧΑΛΚΙΑΔΑΚΗΣ",
            "30006789" to "ΙΑΤΡΙΚΟ ΚΕΝΤΡΟ ΗΡΑΚΛΕΙΟΥ"
        )

        val messages = listOf(
            "ΥΠΕΡΗΦΑΝΕΙΑ TB...",
            "ΠΑΤΣΟΥΡΑΚΗΣ ΕΠΙΠΛΟ 04/2025 (Χ) TB ΣΠΟΤ",
            "ΑΝΤΟΝΑΚΑΚΗΣ ΟΠΤΙΚΑ 03/2025 (Χ) TB ΣΠΟΤ",
            "NOVA ΑΠΟΛΥΜΑΝΤΙΚΗ ΚΑΤΣΑΡΙΔΕΣ 2020 ...TB ΣΠΟΤ",
            "ΦΡΑΓΚΟΥΛΗΣ - ΠΕΡΡΗΣ 17/12 ΧΟΡΗΓΙΑ TB",
            "ΚΡΗΤΗ ΞΕΝΟΔΟΧΕΙΑ ΚΑΛΟΚΑΙΡΙ 2025 TB",
            "ΙΑΤΡΙΚΟ ΚΕΝΤΡΟ CHECK UP TB ΣΠΟΤ"
        )

        val types = listOf(
            "Διαφημίσεις τηλεόρασης",
            "Χορηγίες",
            "Κοινωνικά μηνύματα"
        )

        return (0 until count).map { index ->
            val (code, name) = clients[random.nextInt(clients.size)]
            CommercialItem(
                id = random.nextLong(),
                clientCode = code,
                clientName = name,
                message = messages[random.nextInt(messages.size)],
                durationSeconds = listOf(20, 28, 30, 32, 36, 40, 50)[random.nextInt(7)],
                type = types[random.nextInt(types.size)],
                contract = "ΔΩΡΑ",
                flow = "ΡΟΗ"
            )
        }.toImmutableList()
    }

    /**
     * Calculate daily totals from cell data
     */
    fun calculateDailyTotals(
        cellData: Map<SchedulerKey, SchedulerCellData>
    ): Map<StableDate, DailyStats> {
        val totals = mutableMapOf<StableDate, DailyStats>()

        cellData.forEach { (key, data) ->
            val dateKey = StableDate(key.date)
            val current = totals[dateKey] ?: DailyStats(0, 0)
            totals[dateKey] = DailyStats(
                spotCount = current.spotCount + data.spotCount,
                totalDurationSeconds = current.totalDurationSeconds + data.totalDurationSeconds
            )
        }

        return totals
    }

    /**
     * Get commercials for a specific break and date
     */
    fun getCommercialsForBreak(
        breakId: Long,
        date: LocalDate,
        cellData: Map<SchedulerKey, SchedulerCellData>
    ): ImmutableList<CommercialItem> {
        return cellData[SchedulerKey(breakId, date)]?.commercials ?: persistentListOf()
    }
}
