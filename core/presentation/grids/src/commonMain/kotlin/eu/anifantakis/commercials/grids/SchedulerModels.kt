package eu.anifantakis.commercials.grids

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// ============================================================================
// SCHEDULER DATA MODELS
// ============================================================================

/**
 * Wrapper for daily statistics to ensure stability
 */
@Immutable
data class DailyStats(
    val spotCount: Int,
    val totalDurationSeconds: Int
)

/**
 * Key for scheduler cells to ensure stability.
 *
 * A cell is (TIME, date). There is no break id: a break is not a stored entity,
 * it is the time a spot aired at (the server groups the airings by it).
 */
@Immutable
data class SchedulerKey(
    val time: LocalTime,
    val date: LocalDate
)

/**
 * A ROW of the scheduler - a break, which is to say a TIME.
 *
 * The row may be EMPTY: the hourly / half-hourly views print 08:00 whether or
 * not anything airs there. That is why it has no id - there would be no break
 * for an empty row to take one from.
 */
@Immutable
data class BreakSlot(
    val time: LocalTime,
    val label: String = "",
    val zoneColor: Color = Color.White,
    val zone: BreakZone = BreakZone.DEFAULT
)

/**
 * Break zone types that determine cell coloring
 */
enum class BreakZone {
    PRIME,      // Prime time (pink/magenta)
    STANDARD,   // Standard time (light blue)
    SPECIAL,    // Special promotions (light green)
    DEFAULT     // No special zone (white)
}

/**
 * Data for a single cell in the scheduler grid
 */
@Immutable
data class SchedulerCellData(
    val spotCount: Int = 0,
    val totalDurationSeconds: Int = 0,
    val zoneColor: Color = Color.White,
    val isHighlighted: Boolean = false,
    /** The programme airing at this slot (first placement's), when it has one. */
    val programName: String? = null,
    /**
     * Its IDENTITY. The grid itself never uses it - it is carried so a SCREEN
     * can act on "this cell's programme" (the timetable arms it as the brush)
     * without resolving by name, which is not a key.
     */
    val programId: Long? = null,
    val commercials: ImmutableList<CommercialItem> = persistentListOf()
) {
    val formattedDuration: String
        get() = formatDuration(totalDurationSeconds)
}

/**
 * Represents a commercial item (ad spot)
 */
@Immutable
data class CommercialItem(
    val id: Long,
    val clientCode: String,
    val clientName: String,
    val message: String,
    val durationSeconds: Int,
    /** The spot's own booking type (Break Console). NOT the break's programme -
     *  the Program Flow report prints [SchedulerCellData.programName] instead. */
    val type: String,
    /** Sales item of the contract line (Break Console Τύπος); null -> show [type]. */
    val salesItem: String? = null,
    /** Contract NUMBER (gifts included - the gift marker lives in the item name). */
    val contract: String,
    val isGift: Boolean = false,
    val flow: String,
    /** Legacy calendar_excluded_docs: shown in the grid, kept OFF printed reports. */
    val excludeFromReports: Boolean = false,
)

/**
 * The server's wire value marking a placement as part of the programme FLOW
 * (ΡΟΗ) rather than a paid break. Compared as DATA across the client (grid
 * stats, break-detail console, printed reports) — never shown as UI chrome
 * (the localized column header is `StringKey.DETAIL_COL_FLOW`). Named here,
 * next to [CommercialItem.flow], so the one Greek wire literal has a single home.
 */
const val FLOW_ROH: String = "ΡΟΗ"

// ============================================================================
// DISPLAY MODE ENUMS
// ============================================================================

/**
 * Cell display mode - what to show in each cell
 */
enum class CellDisplayMode {
    SPOT_COUNT,     // Show number of spots (e.g., "6")
    DURATION        // Show total duration (e.g., "03:06")
}

// ============================================================================
// KEYBOARD ACTION ENUM
// ============================================================================

/**
 * Keyboard actions for the data grid
 */
enum class KeyboardAction {
    SELECT,         // Enter pressed
    DELETE,         // Delete/Backspace pressed
    ADD,            // A key pressed (add new item)
    EDIT,           // F2 pressed (start editing)
    COPY,           // Ctrl+C
    PASTE,          // Ctrl+V
    CUT             // Ctrl+X
}

// ============================================================================
// DEFAULT EMPTY CONTENT
// ============================================================================

/**
 * Default composable shown when a grid has no data
 */
@Composable
fun DefaultEmptyContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No data available",
            color = gridPalette().mutedText
        )
    }
}
