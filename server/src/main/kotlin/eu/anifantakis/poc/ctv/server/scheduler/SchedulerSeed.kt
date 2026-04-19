package eu.anifantakis.poc.ctv.server.scheduler

import java.time.DayOfWeek
import java.time.LocalDate

enum class BreakZone { PRIME, STANDARD, SPECIAL, DEFAULT }

data class BreakSlotRow(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String,
    val zone: BreakZone
)

data class CellRow(
    val breakId: Long,
    val date: LocalDate,
    val spotCount: Int,
    val totalDurationSeconds: Int,
    val zoneColorArgb: Int,
    val commercials: List<CommercialRow>
)

data class CommercialRow(
    val id: Long,
    val position: Int,
    val clientCode: String,
    val clientName: String,
    val message: String,
    val durationSeconds: Int,
    val type: String,
    val contract: String,
    val flow: String
)

// ARGB ints matching Compose Color hex values in shared/.../data/SampleData.kt.
private const val ZONE_PINK   = 0xFFFF69B4.toInt()
private const val ZONE_BLUE   = 0xFF87CEEB.toInt()
private const val ZONE_GREEN  = 0xFF90EE90.toInt()
private const val ZONE_ORANGE = 0xFFFFE4B5.toInt()
private const val ZONE_YELLOW = 0xFFFFFF99.toInt()
private const val COLOR_WHITE = 0xFFFFFFFF.toInt()

private fun zoneColorFor(zone: BreakZone): Int = when (zone) {
    BreakZone.PRIME -> ZONE_PINK
    BreakZone.STANDARD -> ZONE_BLUE
    BreakZone.SPECIAL -> ZONE_GREEN
    BreakZone.DEFAULT -> COLOR_WHITE
}

fun breakZoneColorArgb(zone: BreakZone): Int = zoneColorFor(zone)

private fun formatTime(hour: Int, minute: Int): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

fun generateBreaks(): List<BreakSlotRow> {
    val out = mutableListOf<BreakSlotRow>()
    var id = 1L
    for (hour in 0..23) {
        for (minute in listOf(0, 15, 30, 45)) {
            val zone = when {
                hour in 20..23 -> BreakZone.PRIME
                hour in 10..14 -> BreakZone.STANDARD
                hour in 18..19 -> BreakZone.SPECIAL
                else -> BreakZone.DEFAULT
            }
            out += BreakSlotRow(
                id = id++,
                hour = hour,
                minute = minute,
                label = formatTime(hour, minute),
                zone = zone
            )
        }
    }
    return out
}

private val clients = listOf(
    "30002310" to "ΚΟΙΝΩΝΙΚΑ ΠΕΛΑΤΕΣ ΔΙΑΦΟΡΟΙ",
    "30001604" to "ΥΙΟΙ Κ. ΠΑΤΣΟΥΡΑΚΗ Ο.Ε",
    "30002918" to "ΑΝΤΟΝΑΚΑΚΗ ΑΙΚΑΤ & ΚΟΝ. Ο.Ε",
    "30003875" to "NOVA ΑΠΟΛΥΜΑΝΤΙΚΗ Ε.Ε",
    "30004521" to "ΚΡΗΤΗ ΞΕΝΟΔΟΧΕΙΑ Α.Ε",
    "30005123" to "SUPER MARKET ΧΑΛΚΙΑΔΑΚΗΣ",
    "30006789" to "ΙΑΤΡΙΚΟ ΚΕΝΤΡΟ ΗΡΑΚΛΕΙΟΥ"
)

private val messages = listOf(
    "ΥΠΕΡΗΦΑΝΕΙΑ TB...",
    "ΠΑΤΣΟΥΡΑΚΗΣ ΕΠΙΠΛΟ 04/2025 (Χ) TB ΣΠΟΤ",
    "ΑΝΤΟΝΑΚΑΚΗΣ ΟΠΤΙΚΑ 03/2025 (Χ) TB ΣΠΟΤ",
    "NOVA ΑΠΟΛΥΜΑΝΤΙΚΗ ΚΑΤΣΑΡΙΔΕΣ 2020 ...TB ΣΠΟΤ",
    "ΦΡΑΓΚΟΥΛΗΣ - ΠΕΡΡΗΣ 17/12 ΧΟΡΗΓΙΑ TB",
    "ΚΡΗΤΗ ΞΕΝΟΔΟΧΕΙΑ ΚΑΛΟΚΑΙΡΙ 2025 TB",
    "ΙΑΤΡΙΚΟ ΚΕΝΤΡΟ CHECK UP TB ΣΠΟΤ"
)

private val types = listOf(
    "Διαφημίσεις τηλεόρασης",
    "Χορηγίες",
    "Κοινωνικά μηνύματα"
)

private val durations = listOf(20, 28, 30, 32, 36, 40, 50)

private fun daysInMonth(year: Int, month: Int): Int = when {
    month == 2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    month in listOf(4, 6, 9, 11) -> 30
    else -> 31
}

/**
 * Mirrors shared/.../data/SampleData.generateCellData byte-for-byte.
 * Order of random draws MUST match the original (weekend check → spotCount → avgDuration → commercials).
 */
fun generateMonth(breaks: List<BreakSlotRow>, year: Int, month: Int): List<CellRow> {
    val random = kotlin.random.Random(year * 100 + month)
    val out = mutableListOf<CellRow>()
    for (day in 1..daysInMonth(year, month)) {
        val date = LocalDate.of(year, month, day)
        val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        for (b in breaks) {
            if (random.nextFloat() > 0.35f) continue
            val spotCount = random.nextInt(1, 16)
            val avgDuration = random.nextInt(20, 60)
            val totalDuration = spotCount * avgDuration

            val zoneColorArgb = when {
                b.zone == BreakZone.PRIME -> ZONE_PINK
                b.zone == BreakZone.SPECIAL -> ZONE_GREEN
                isWeekend -> ZONE_ORANGE
                spotCount > 10 -> ZONE_YELLOW
                else -> COLOR_WHITE
            }

            val commercials = (0 until spotCount).map { i ->
                val (code, name) = clients[random.nextInt(clients.size)]
                CommercialRow(
                    id = random.nextLong(),
                    position = i,
                    clientCode = code,
                    clientName = name,
                    message = messages[random.nextInt(messages.size)],
                    durationSeconds = durations[random.nextInt(durations.size)],
                    type = types[random.nextInt(types.size)],
                    contract = "ΔΩΡΑ",
                    flow = "ΡΟΗ"
                )
            }

            out += CellRow(
                breakId = b.id,
                date = date,
                spotCount = spotCount,
                totalDurationSeconds = totalDuration,
                zoneColorArgb = zoneColorArgb,
                commercials = commercials
            )
        }
    }
    return out
}
