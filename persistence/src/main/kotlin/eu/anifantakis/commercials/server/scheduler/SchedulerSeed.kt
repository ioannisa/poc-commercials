package eu.anifantakis.commercials.server.scheduler

import java.time.LocalDate
import java.time.LocalTime

enum class BreakZone { PRIME, STANDARD, SPECIAL, DEFAULT }

/**
 * A BREAK - which is not a stored entity but whatever a GROUP BY on the airing
 * time returns (see GroupDb). It IS a time, and carries nothing else: its label
 * and its zone are FUNCTIONS of that time, computed here so that exactly one
 * place in the codebase decides them.
 *
 * There used to be a `break_slots` row with a stored `label` ("00:05" - the time
 * restated) and a stored `zone` (a hardcoded `when` on the hour, written out
 * twice: once by the demo seeder and once by the migrator, free to drift apart).
 * Neither held any information the time did not already have.
 */
data class BreakTimeRow(val time: LocalTime) {
    val hour: Int get() = time.hour
    val minute: Int get() = time.minute
    val label: String get() = formatHhMm(time)
    val zone: BreakZone get() = zoneOf(time.hour)
}

fun formatHhMm(time: LocalTime): String =
    "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"

/** The zone rule. ONE definition - the migrator no longer keeps a copy. */
fun zoneOf(hour: Int): BreakZone = when (hour) {
    in 20..23 -> BreakZone.PRIME
    in 10..14 -> BreakZone.STANDARD
    in 18..19 -> BreakZone.SPECIAL
    else -> BreakZone.DEFAULT
}

/**
 * READ-MODEL row shapes returned by StationDb.loadMonth - the grid the client
 * renders. Since the normalized-schema evolution these are DERIVED from
 * placements ⋈ spots ⋈ customers ⋈ contracts, not stored.
 *
 * A cell is keyed by (time, date) - the break's time, because that is all a
 * break is.
 */
data class CellRow(
    val time: LocalTime,
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
    /**
     * PROGRAMME type (programtypes, e.g. ΚΛΕΨΑ / ΞΕΝΗ ΤΑΙΝΙΑ) - a property of
     * the SLOT, printed on the Program Flow report. It is NOT the spot's Τύπος:
     * a programme is not a product. Do not render it in the Break Console's
     * Τύπος column (see [salesItem]).
     */
    val type: String,
    /**
     * The SALES item of the spot's contract line (ERP STI name, e.g.
     * 'Διαφ. TV Κρήτη Σ73.002') - the Break Console's Τύπος. Null when the ERP
     * enrichment has not stamped it; a gift then shows the gift marker, else the
     * cell is blank ("unknown item"). NEVER falls back to [type] - that
     * category error made the whole column read a programme as the product
     * (fixed in CommercialDetailScreen and the `spots_in_break` MCP tool).
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

/**
 * The times a DEMO station's invented airings land on (a real station's come
 * from its data). This is not a break catalog - nothing is stored - it is the
 * generator's choice of when to pretend spots aired, so a demo group has a
 * plausible-looking grid.
 */
fun demoBreakTimes(): List<LocalTime> =
    (0..23).flatMap { hour -> listOf(0, 15, 30, 45).map { LocalTime.of(hour, it) } }

// ──────────────────────────────── the grid's rows ───────────────────────────

/** ≙ the legacy console's "Προβολή κάθε: 1 Ώρα / Μισή Ώρα / Διάλειμμα". */
enum class GridViewMode { CONDENSED, HALF_HOURLY, HOURLY }

/**
 * THE ROWS OF THE GRID = a fixed SCAFFOLD ∪ the period's REAL breaks.
 *
 * This is the whole of what the `break_slots` table used to be for, and it is a
 * pure function of (what aired, which view, where the day starts) - which is why
 * it needed no table:
 *
 *  - CONDENSED ("Διάλειμμα") - no scaffold. Only rows that a break produced.
 *  - HOURLY ("1 Ώρα")        - the 24 :00 rows, drawn even when empty.
 *  - HALF_HOURLY ("Μισή Ώρα")- the 48 :00/:30 rows, drawn even when empty.
 *
 * A real break OFF the scaffold keeps its own row, in time order - so a 12:20
 * break lands between 12:00 and 13:00 rather than being rounded into either.
 * And [emptyRowsFrom] holds the scaffold back until the broadcast day starts, so
 * the empty small hours are not printed while the real 00:05 break still is.
 *
 * The screenshot this reproduces (Crete TV, Δεκέμβριος 2025, "Μισή Ώρα",
 * emptyRowsFrom 07:00) reads: 00:05, 00:30, 01:00, 01:30, 01:45 … 04:00, then
 * 07:00, 07:30, 08:00 (empty, scaffold), 09:00 … - with 04:30-06:30 absent
 * entirely, being neither a break nor inside the scaffold.
 */
fun gridRows(
    breakTimes: List<LocalTime>,
    mode: GridViewMode,
    emptyRowsFrom: LocalTime,
): List<BreakTimeRow> {
    val scaffold = when (mode) {
        GridViewMode.CONDENSED -> emptyList()
        GridViewMode.HOURLY -> (0..23).map { LocalTime.of(it, 0) }
        GridViewMode.HALF_HOURLY -> (0..23).flatMap { listOf(LocalTime.of(it, 0), LocalTime.of(it, 30)) }
    }.filter { !it.isBefore(emptyRowsFrom) }

    return (scaffold + breakTimes).distinct().sorted().map { BreakTimeRow(it) }
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
    val date: LocalDate,
    val time: LocalTime,
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
    breakTimes: List<LocalTime>,
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
        for (time in breakTimes) {
            if (random.nextFloat() > 0.35f) continue
            val spotCount = random.nextInt(1, 16)
            for (position in 0 until spotCount) {
                val spot = spots[random.nextInt(spots.size)]
                out += PlacementSeed(
                    spotId = spot.id,
                    date = date,
                    time = time,
                    position = position,
                    durationSeconds = spot.durationSeconds,
                )
            }
        }
    }
    return out
}
