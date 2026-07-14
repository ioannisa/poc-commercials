package eu.anifantakis.commercials.grids

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import androidx.compose.ui.tooling.preview.Preview

/*
 * Previews for the two grids - the app's largest components, and the two that a
 * screenshot cannot be reasoned about without: the scheduler grid's whole job is
 * to lay a month of breaks against a month of days, and the data grid's is frozen
 * columns, sorting and totals. Both are far too expensive to check by launching
 * the app and clicking to the right month.
 *
 * They live in their own file rather than at the bottom of the components': those
 * two files are already among the longest in the repo.
 */

private val DECEMBER_2025_BREAKS = persistentListOf(
    BreakSlot(time = LocalTime(8, 0), label = "08:00", zoneColor = Color(0xFFE8F0FE), zone = BreakZone.DEFAULT),
    BreakSlot(time = LocalTime(12, 30), label = "12:30", zoneColor = Color(0xFFFFF3E0), zone = BreakZone.STANDARD),
    BreakSlot(time = LocalTime(18, 0), label = "18:00", zoneColor = Color(0xFFE6F4EA), zone = BreakZone.SPECIAL),
    BreakSlot(time = LocalTime(20, 30), label = "20:30", zoneColor = Color(0xFFFDE7E9), zone = BreakZone.PRIME),
)

/** A handful of airings, so the cells show counts, colours and a programme name. */
private val DECEMBER_2025_CELLS = persistentMapOf(
    SchedulerKey(LocalTime(20, 30), LocalDate(2025, 12, 1)) to SchedulerCellData(
        spotCount = 4,
        totalDurationSeconds = 120,
        zoneColor = Color(0xFFFDE7E9),
        programName = "Evening News",
    ),
    SchedulerKey(LocalTime(20, 30), LocalDate(2025, 12, 2)) to SchedulerCellData(
        spotCount = 7,
        totalDurationSeconds = 215,
        zoneColor = Color(0xFFFDE7E9),
        programName = "Evening News",
    ),
    SchedulerKey(LocalTime(12, 30), LocalDate(2025, 12, 2)) to SchedulerCellData(
        spotCount = 2,
        totalDurationSeconds = 45,
        zoneColor = Color(0xFFFFF3E0),
    ),
    SchedulerKey(LocalTime(8, 0), LocalDate(2025, 12, 3)) to SchedulerCellData(
        spotCount = 1,
        totalDurationSeconds = 30,
        zoneColor = Color(0xFFE8F0FE),
    ),
)

@Preview
@Composable
private fun LazySchedulerGridPreview() = AppPreview(padded = false) {
    LazySchedulerGrid(
        breaks = DECEMBER_2025_BREAKS,
        cellData = DECEMBER_2025_CELLS,
        // The black marker for cells this session touched - it exists precisely to
        // be seen against the untouched ones, so a preview without it is useless.
        modifiedCells = persistentSetOf(SchedulerKey(LocalTime(20, 30), LocalDate(2025, 12, 2))),
        year = 2025,
        month = 12,
    )
}

/** The same month with `showTimes` on: cells read MM:SS instead of a spot count. */
@Preview
@Composable
private fun LazySchedulerGridShowingTimesPreview() = AppPreview(padded = false) {
    LazySchedulerGrid(
        breaks = DECEMBER_2025_BREAKS,
        cellData = DECEMBER_2025_CELLS,
        year = 2025,
        month = 12,
        showTimes = true,
    )
}

/**
 * A month with breaks but NO airings. Worth its own preview: an empty grid is what
 * an operator sees on the 1st of every month, and it is the state where a layout
 * that only works when it is full falls apart.
 */
@Preview
@Composable
private fun LazySchedulerGridEmptyMonthPreview() = AppPreview(padded = false) {
    LazySchedulerGrid(
        breaks = DECEMBER_2025_BREAKS,
        cellData = persistentMapOf(),
        year = 2025,
        month = 12,
    )
}

private data class PreviewSpot(
    val code: String,
    val customer: String,
    val message: String,
    val duration: String,
    val contract: String,
)

private val PREVIEW_SPOTS = persistentListOf(
    PreviewSpot("A102", "Anifantakis Foods", "Summer campaign", "00:30", "2025/114"),
    PreviewSpot("B037", "Crete Motors", "New model launch", "00:20", "2025/091"),
    PreviewSpot("C255", "Aegean Travel", "Winter offers", "00:15", "2025/130"),
    PreviewSpot("D008", "Heraklion Bank", "Mortgage rates", "00:45", "2025/077"),
)

private val PREVIEW_COLUMNS = persistentListOf(
    ColumnDef<PreviewSpot>(
        id = "code",
        header = "Code",
        frozen = FrozenPosition.LEFT,
        extractor = { it.code },
    ),
    ColumnDef<PreviewSpot>(id = "customer", header = "Customer", extractor = { it.customer }),
    ColumnDef<PreviewSpot>(id = "message", header = "Message", extractor = { it.message }),
    ColumnDef<PreviewSpot>(id = "duration", header = "Duration", extractor = { it.duration }),
    ColumnDef<PreviewSpot>(id = "contract", header = "Contract", extractor = { it.contract }),
)

@Preview
@Composable
private fun EnhancedDataGridPreview() = AppPreview(padded = false) {
    EnhancedDataGrid(
        items = PREVIEW_SPOTS,
        columns = PREVIEW_COLUMNS,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Empty: the state where the header row must still stand on its own. */
@Preview
@Composable
private fun EnhancedDataGridEmptyPreview() = AppPreview(padded = false) {
    EnhancedDataGrid(
        items = persistentListOf<PreviewSpot>(),
        columns = PREVIEW_COLUMNS,
        modifier = Modifier.fillMaxWidth(),
    )
}
