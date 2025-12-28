package eu.anifantakis.poc.ctv.grids

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
 * Key for scheduler cells to ensure stability
 */
@Immutable
data class SchedulerKey(
    val breakId: Long,
    val date: LocalDate
)

/**
 * Represents a break slot in the scheduler (a time slot for commercials)
 */
@Immutable
data class BreakSlot(
    val id: Long,
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
    val type: String,
    val contract: String,
    val flow: String
)

// ============================================================================
// DISPLAY MODE ENUMS
// ============================================================================

/**
 * Display mode for the scheduler grid - controls which break rows are visible
 */
enum class SchedulerDisplayMode {
    CONDENSED,      // Only show breaks that have spots
    HALF_HOURLY,    // Show :00 and :30 breaks plus any with spots
    HOURLY          // Show :00 breaks plus any with spots
}

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
            color = Color.Gray
        )
    }
}
