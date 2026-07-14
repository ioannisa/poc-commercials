package eu.anifantakis.commercials.server.scheduler

import java.time.LocalDate

enum class BreakZone { PRIME, STANDARD, SPECIAL, DEFAULT }

data class BreakSlotRow(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String,
    val zone: BreakZone
)

/**
 * A break to be CREATED - it has no id yet.
 *
 * Break ids used to be computed by the seeder (1..96, identical in every
 * station's own schema). Now that a group's stations share one database the
 * database assigns them, so the seed describes the slot and reads the id back.
 */
data class BreakTemplate(
    val hour: Int,
    val minute: Int,
    val label: String,
    val zone: BreakZone
)

/**
 * READ-MODEL row shapes returned by StationDb.loadMonth - the grid the client
 * renders. Since the normalized-schema evolution these are DERIVED from
 * placements ⋈ spots ⋈ customers ⋈ contracts, not stored.
 */
data class CellRow(
    val breakId: Long,
    val date: LocalDate,
    val spotCount: Int,
    val totalDurationSeconds: Int,
    val zoneColorArgb: Int,
    /** The programme airing at this slot (first placement's), when it has one. */
    val programName: String? = null,
    val commercials: List<CommercialRow>
)

data class CommercialRow(
    val id: Long,
    /** The spot (creative) this placement airs - customer emails are per spot. */
    val spotId: Long,
    val position: Int,
    val clientCode: String,
    val clientName: String,
    val message: String,
    /**
     * The SPOT's duration, NOT the airing's - matching the legacy Break Console,
     * whose "sec" column and ΣΥΝΟΛΙΚΗ ΔΙΑΡΚΕΙΑ both read the message.
     *
     * The legacy `schedule` row carries its own `durationSecs`, and it disagrees
     * with the message on 22.68% of airings. Mostly that is a stale snapshot
     * (the length was corrected on the message afterwards and the old schedule
     * rows kept the old value), though 1,530 spots do genuinely vary per airing.
     * Both readings are defensible; the owner chose parity with the legacy tool
     * (2026-07-13), so `placements.duration_seconds` is preserved in the data but
     * is deliberately NOT what this row - or any total built from it - reports.
     */
    val durationSeconds: Int,
    /** PROGRAMME type (programtypes) - printed on the Program Flow report. */
    val type: String,
    /**
     * The SALES item of the spot's contract line (ERP STI name, e.g.
     * 'Διαφ. TV Κρήτη Σ73.002') - the Break Console's Τύπος. Null when the
     * ERP enrichment has not stamped it; displays fall back to [type].
     */
    val salesItem: String? = null,
    /** The contract number - the Break Console's Σύμβαση (a NUMBER even for gifts). */
    val contract: String,
    val isGift: Boolean = false,
    /** Legacy calendar_excluded_docs: aired normally but kept off printed reports. */
    val excludeFromReports: Boolean = false,
    val flow: String,
    /** Programme identity (name + operator colour), when the placement has one. */
    val programName: String? = null,
    val programColorArgb: Int? = null,
    /**
     * The contract's paying party (legacy `traid` - usually the customer
     * itself, but an agency in "triangular" deals). Null when the spot has no
     * contract line.
     */
    val payerCode: String? = null,
    val payerName: String? = null,
)

// ARGB ints matching Compose Color hex values in shared/.../data/SampleData.kt.
private const val ZONE_PINK   = 0xFFFF69B4.toInt()
private const val ZONE_BLUE   = 0xFF87CEEB.toInt()
private const val ZONE_GREEN  = 0xFF90EE90.toInt()
private const val ZONE_ORANGE = 0xFFFFE4B5.toInt()
private const val ZONE_YELLOW = 0xFFFFFF99.toInt()
private const val COLOR_WHITE = 0xFFFFFFFF.toInt()

fun breakZoneColorArgb(zone: BreakZone): Int = when (zone) {
    BreakZone.PRIME -> ZONE_PINK
    BreakZone.STANDARD -> ZONE_BLUE
    BreakZone.SPECIAL -> ZONE_GREEN
    BreakZone.DEFAULT -> COLOR_WHITE
}

/**
 * Cell background colour, computed at READ time from the break's zone, the
 * weekday and the cell's spot count (same rules the old stored demo data
 * used, so the grid looks identical).
 */
fun cellColorArgb(zone: BreakZone, isWeekend: Boolean, spotCount: Int): Int = when {
    zone == BreakZone.PRIME -> ZONE_PINK
    zone == BreakZone.SPECIAL -> ZONE_GREEN
    isWeekend -> ZONE_ORANGE
    spotCount > 10 -> ZONE_YELLOW
    else -> COLOR_WHITE
}

private fun formatTime(hour: Int, minute: Int): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

fun generateBreaks(): List<BreakTemplate> {
    val out = mutableListOf<BreakTemplate>()
    for (hour in 0..23) {
        for (minute in listOf(0, 15, 30, 45)) {
            val zone = when {
                hour in 20..23 -> BreakZone.PRIME
                hour in 10..14 -> BreakZone.STANDARD
                hour in 18..19 -> BreakZone.SPECIAL
                else -> BreakZone.DEFAULT
            }
            out += BreakTemplate(
                hour = hour,
                minute = minute,
                label = formatTime(hour, minute),
                zone = zone
            )
        }
    }
    return out
}

// ────────────────────────────── demo catalog ────────────────────────────────
// Deterministic: the same customers/spots on every boot, so month seeding stays
// idempotent and concurrent-safe. Mirrors the legacy world: customers (with
// ΑΦΜ), gift contracts, and a spot catalog (≙ `messages`).
//
// The customers and contracts belong to the GROUP - every station of the group
// shares them, which is what the model is for. Only the spots are per station.

data class CustomerSeed(val code: String, val name: String, val vat: String)

val demoCustomers = listOf(
    CustomerSeed("30002310", "ΚΟΙΝΩΝΙΚΑ ΠΕΛΑΤΕΣ ΔΙΑΦΟΡΟΙ", "099111222"),
    CustomerSeed("30001604", "ΥΙΟΙ Κ. ΠΑΤΣΟΥΡΑΚΗ Ο.Ε", "099333444"),
    CustomerSeed("30002918", "ΑΝΤΟΝΑΚΑΚΗ ΑΙΚΑΤ & ΚΟΝ. Ο.Ε", "099555666"),
    CustomerSeed("30003875", "NOVA ΑΠΟΛΥΜΑΝΤΙΚΗ Ε.Ε", "099777888"),
    CustomerSeed("30004521", "ΚΡΗΤΗ ΞΕΝΟΔΟΧΕΙΑ Α.Ε", "099999000"),
    CustomerSeed("30005123", "SUPER MARKET ΧΑΛΚΙΑΔΑΚΗΣ", "098123456"),
    CustomerSeed("30006789", "ΙΑΤΡΙΚΟ ΚΕΝΤΡΟ ΗΡΑΚΛΕΙΟΥ", "098654321"),
)

val demoMessages = listOf(
    "ΥΠΕΡΗΦΑΝΕΙΑ TB...",
    "ΠΑΤΣΟΥΡΑΚΗΣ ΕΠΙΠΛΟ 04/2025 (Χ) TB ΣΠΟΤ",
    "ΑΝΤΟΝΑΚΑΚΗΣ ΟΠΤΙΚΑ 03/2025 (Χ) TB ΣΠΟΤ",
    "NOVA ΑΠΟΛΥΜΑΝΤΙΚΗ ΚΑΤΣΑΡΙΔΕΣ 2020 ...TB ΣΠΟΤ",
    "ΦΡΑΓΚΟΥΛΗΣ - ΠΕΡΡΗΣ 17/12 ΧΟΡΗΓΙΑ TB",
    "ΚΡΗΤΗ ΞΕΝΟΔΟΧΕΙΑ ΚΑΛΟΚΑΙΡΙ 2025 TB",
    "ΙΑΤΡΙΚΟ ΚΕΝΤΡΟ CHECK UP TB ΣΠΟΤ",
)

val demoSpotTypes = listOf(
    "Διαφημίσεις τηλεόρασης",
    "Χορηγίες",
    "Κοινωνικά μηνύματα",
)

val demoDurations = listOf(20, 28, 30, 32, 36, 40, 50)

/** How many catalog spots each station gets. */
const val DEMO_SPOTS_PER_STATION = 28

/**
 * Product lines on each demo contract. More than one on purpose: a station's
 * spots charge to the line matching its index in the group, so a two-station
 * demo group reproduces the real shape - ONE contract, a TV line and a radio
 * line - without needing a migration to show it.
 */
const val DEMO_LINES_PER_CONTRACT = 2

private fun daysInMonth(year: Int, month: Int): Int = when {
    month == 2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    month in listOf(4, 6, 9, 11) -> 30
    else -> 31
}

/** A catalog spot as needed by placement generation. */
data class SpotRef(val id: Long, val durationSeconds: Int)

/** A generated placement, ready to insert (write model, ≙ legacy `schedule`). */
data class PlacementSeed(
    val spotId: Long,
    val breakId: Long,
    val date: LocalDate,
    val position: Int,
    val durationSeconds: Int,
)

/**
 * Deterministic demo placement generator. [stationSeed] varies the RNG per
 * hosted station so different schemas hold visibly different data;
 * determinism per (station, month) is what makes concurrent seeding safe
 * (see StationDb.ensureMonthSeeded).
 */
fun generateMonthPlacements(
    breaks: List<BreakSlotRow>,
    spots: List<SpotRef>,
    year: Int,
    month: Int,
    stationSeed: Int = 0,
): List<PlacementSeed> {
    if (spots.isEmpty()) return emptyList()
    val random = kotlin.random.Random(stationSeed * 1_000_003 + year * 100 + month)
    val out = mutableListOf<PlacementSeed>()
    for (day in 1..daysInMonth(year, month)) {
        val date = LocalDate.of(year, month, day)
        for (b in breaks) {
            if (random.nextFloat() > 0.35f) continue
            val spotCount = random.nextInt(1, 16)
            for (position in 0 until spotCount) {
                val spot = spots[random.nextInt(spots.size)]
                out += PlacementSeed(
                    spotId = spot.id,
                    breakId = b.id,
                    date = date,
                    position = position,
                    durationSeconds = spot.durationSeconds,
                )
            }
        }
    }
    return out
}
