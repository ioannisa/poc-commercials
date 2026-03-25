package eu.anifantakis.poc.ctv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.anifantakis.poc.ctv.data.SampleData
import eu.anifantakis.poc.ctv.grids.*
import eu.anifantakis.poc.ctv.reports.ui.ReportToolbar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/**
 * Timetable screen showing the scheduler grid
 * Similar to the first screenshot - monthly view with breaks as rows and days as columns
 * Now with keyboard navigation support:
 * - Arrow keys: Navigate selection
 * - Enter: Open/activate selected cell
 * - A: Add spot (adds +1, shows black background)
 * - D: Delete spot (reverts to original)
 */
@Composable
fun TimetableScreen(
    year: Int,
    month: Int,
    breaks: ImmutableList<BreakSlot>,
    cellData: SnapshotStateMap<SchedulerKey, SchedulerCellData>,
    originalCellData: ImmutableMap<SchedulerKey, SchedulerCellData>,
    modifiedCells: SnapshotStateSet<SchedulerKey>,
    selectedRow: Int,
    selectedColumn: Int,
    onYearChange: (Int) -> Unit,
    onMonthChange: (Int) -> Unit,
    onSelectionChange: (row: Int, col: Int) -> Unit,
    onCellClick: (breakId: Long, breakTime: String, date: LocalDate, spotCount: Int) -> Unit
) {
    // Daily totals - recalculate when cellData changes
    // We need to observe cellData changes. cellData is a SnapshotStateMap.
    // However, derivedStateOf might not observe map content deep changes if we just pass the map reference?
    // SampleData.calculateDailyTotals iterates the map. SnapshotStateMap iteration is observable.
    val dailyTotals by remember(year, month) {
        derivedStateOf {
            SampleData.calculateDailyTotals(cellData).toImmutableMap()
        }
    }

    // Greek month names
    val greekMonths = listOf(
        "Ιανουάριος", "Φεβρουάριος", "Μάρτιος", "Απρίλιος",
        "Μάιος", "Ιούνιος", "Ιούλιος", "Αύγουστος",
        "Σεπτέμβριος", "Οκτώβριος", "Νοέμβριος", "Δεκέμβριος"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header with keyboard shortcut hints and report toolbar
        KeyboardEnabledHeader(
            year = year,
            month = month,
            monthName = greekMonths[month - 1],
            breaks = breaks,
            cellData = cellData.toImmutableMap(),
            onPreviousMonth = {
                if (month == 1) {
                    onMonthChange(12)
                    onYearChange(year - 1)
                } else {
                    onMonthChange(month - 1)
                }
            },
            onNextMonth = {
                if (month == 12) {
                    onMonthChange(1)
                    onYearChange(year + 1)
                } else {
                    onMonthChange(month + 1)
                }
            }
        )

        // The scheduler grid with keyboard navigation (using LazyColumn for performance)
        LazySchedulerGrid(
            breaks = breaks,
            cellData = cellData.toImmutableMap(),
            modifiedCells = modifiedCells.toImmutableSet(),
            year = year,
            month = month,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            breakColumnWidth = 70.dp,
            dayColumnWidth = 42.dp,
            rowHeight = 28.dp,
            headerHeight = 50.dp,
            initialSelectedRow = selectedRow,
            initialSelectedColumn = selectedColumn,
            onSelectionChange = { row, col ->
                onSelectionChange(row, col)
            },
            onCellClick = { breakSlot, date, data ->
                // Single click - just select the cell (handled by grid)
            },
            onCellDoubleClick = { breakSlot, date, data ->
                // Double click or Enter key - navigate to detail screen
                val spotCount = data?.spotCount ?: 0
                if (spotCount > 0) {
                    onCellClick(
                        breakSlot.id,
                        formatTime(breakSlot.time.hour, breakSlot.time.minute),
                        date,
                        spotCount
                    )
                }
            },
            onAddSpot = { breakSlot, date ->
                // 'A' key pressed - add a new spot
                val key = SchedulerKey(breakSlot.id, date)
                val currentData = cellData[key] ?: SchedulerCellData()

                // Create new commercial item
                val newCommercial = CommercialItem(
                    id = Clock.System.now().toEpochMilliseconds(),
                    clientCode = "NEW",
                    clientName = "NEW SPOT",
                    message = "NEW SPOT",
                    durationSeconds = 30,
                    type = "New",
                    contract = "",
                    flow = ""
                )

                // Update cell data with +1 spot
                cellData[key] = currentData.copy(
                    spotCount = currentData.spotCount + 1,
                    commercials = (currentData.commercials + newCommercial).toImmutableList()
                )

                // Mark as modified (for black background)
                modifiedCells.add(key)

                println("Added spot at ${breakSlot.id}, $date - now ${cellData[key]?.spotCount} spots")
            },
            onDeleteSpot = { breakSlot, date ->
                // 'D' key pressed - revert to original
                val key = SchedulerKey(breakSlot.id, date)

                // Restore original data
                val originalData = originalCellData[key]
                if (originalData != null) {
                    cellData[key] = originalData
                } else {
                    cellData.remove(key)
                }

                // Remove from modified set (restore original background)
                modifiedCells.remove(key)

                println("Reverted spot at ${breakSlot.id}, $date")
            },
            dailyTotals = dailyTotals,
            contextMenuItems = { breakSlot, date, data ->
                val spotCount = data?.spotCount ?: 0
                val key = SchedulerKey(breakSlot.id, date)
                val isModified = modifiedCells.contains(key)

                listOf(
                    // Open/View details
                    ContextMenuEntry.Item(
                        label = "Open Details",
                        icon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp)) },
                        shortcut = "Enter",
                        enabled = spotCount > 0
                    ) {
                        if (spotCount > 0) {
                            onCellClick(
                                breakSlot.id,
                                formatTime(breakSlot.time.hour, breakSlot.time.minute),
                                date,
                                spotCount
                            )
                        }
                    },

                    // === SEPARATOR ===
                    ContextMenuEntry.Separator,

                    // Edit submenu
                    ContextMenuEntry.SubMenu(
                        label = "Edit",
                        icon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)) },
                        items = listOf(
                            ContextMenuEntry.Item(
                                label = "Add Spot",
                                icon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) },
                                shortcut = "A"
                            ) {
                                val currentData = cellData[key] ?: SchedulerCellData()
                                val newCommercial = CommercialItem(
                                    id = Clock.System.now().toEpochMilliseconds(),
                                    clientCode = "NEW",
                                    clientName = "NEW SPOT",
                                    message = "NEW SPOT",
                                    durationSeconds = 30,
                                    type = "New",
                                    contract = "",
                                    flow = ""
                                )
                                cellData[key] = currentData.copy(
                                    spotCount = currentData.spotCount + 1,
                                    commercials = (currentData.commercials + newCommercial).toImmutableList()
                                )
                                modifiedCells.add(key)
                            },
                            ContextMenuEntry.Item(
                                label = "Delete Spot",
                                icon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp)) },
                                enabled = spotCount > 0
                            ) {
                                println("Delete spot from: ${breakSlot.id} on $date")
                            },
                            ContextMenuEntry.Separator,
                            ContextMenuEntry.Item(
                                label = "Revert Changes",
                                icon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp)) },
                                shortcut = "D",
                                enabled = isModified
                            ) {
                                val originalData = originalCellData[key]
                                if (originalData != null) {
                                    cellData[key] = originalData
                                } else {
                                    cellData.remove(key)
                                }
                                modifiedCells.remove(key)
                            }
                        )
                    ),

                    // === SEPARATOR ===
                    ContextMenuEntry.Separator,

                    // Clipboard operations
                    ContextMenuEntry.Item(
                        label = "Copy",
                        icon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) },
                        shortcut = "⌘C",
                        enabled = spotCount > 0
                    ) {
                        println("Copy: ${breakSlot.id} on $date")
                    },
                    ContextMenuEntry.Item(
                        label = "Cut",
                        icon = { Icon(Icons.Default.ContentCut, null, modifier = Modifier.size(16.dp)) },
                        shortcut = "⌘X",
                        enabled = spotCount > 0
                    ) {
                        println("Cut: ${breakSlot.id} on $date")
                    },
                    ContextMenuEntry.Item(
                        label = "Paste",
                        icon = { Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(16.dp)) },
                        shortcut = "⌘V"
                    ) {
                        println("Paste at: ${breakSlot.id} on $date")
                    },

                    // === SEPARATOR ===
                    ContextMenuEntry.Separator,

                    // More options submenu
                    ContextMenuEntry.SubMenu(
                        label = "More Options",
                        icon = { Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(16.dp)) },
                        items = listOf(
                            ContextMenuEntry.Item(
                                label = "View History",
                                icon = { Icon(Icons.Default.History, null, modifier = Modifier.size(16.dp)) }
                            ) {
                                println("View history for: ${breakSlot.id} on $date")
                            },
                            ContextMenuEntry.Item(
                                label = "Share",
                                icon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp)) }
                            ) {
                                println("Share: ${breakSlot.id} on $date")
                            },
                            ContextMenuEntry.Separator,
                            ContextMenuEntry.Item(
                                label = "Cell Info",
                                icon = { Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp)) }
                            ) {
                                println("Info: Break ${breakSlot.id}, Date: $date, Spots: $spotCount")
                            }
                        )
                    )
                )
            }
        )
    }
}

@Composable
private fun KeyboardEnabledHeader(
    year: Int,
    month: Int,
    monthName: String,
    breaks: ImmutableList<BreakSlot>,
    cellData: ImmutableMap<SchedulerKey, SchedulerCellData>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    // Current selected date for reports (use first day of month as default)
    val selectedDate = LocalDate(year, month, 1).toStable()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFE8E8E8),
        tonalElevation = 4.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Month navigation
                IconButton(onClick = onPreviousMonth) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous month"
                    )
                }

                Text(
                    text = "$monthName $year",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    modifier = Modifier.width(200.dp)
                )

                IconButton(onClick = onNextMonth) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next month"
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Keyboard shortcut hints
                Column {
                    Text(
                        text = "Arrows: Navigate | Enter: Open | A: Add | D: Delete",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = "Click grid to focus, then use keyboard",
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
            }

            // Report toolbar
            ReportToolbar(
                selectedDate = selectedDate,
                breaks = breaks,
                cellData = cellData,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun SimpleHeader(
    year: Int,
    month: Int,
    monthName: String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFE8E8E8),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Month navigation
            IconButton(onClick = onPreviousMonth) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Previous month"
                )
            }

            Text(
                text = "$monthName $year",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.width(200.dp)
            )

            IconButton(onClick = onNextMonth) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Next month"
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Double-click a cell to see details",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun TimetableHeader(
    year: Int,
    month: Int,
    monthName: String,
    isLocked: Boolean,
    displayMode: SchedulerDisplayMode,
    cellDisplayMode: CellDisplayMode,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToggleLock: () -> Unit,
    onDisplayModeChange: (SchedulerDisplayMode) -> Unit,
    onCellDisplayModeChange: (CellDisplayMode) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFE8E8E8),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Month navigation
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onPreviousMonth) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous month"
                    )
                }

                Text(
                    text = "$monthName $year",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.width(180.dp)
                )

                IconButton(onClick = onNextMonth) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next month"
                    )
                }
            }

            // Display mode selector
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("View:", fontSize = 12.sp)

                FilterChip(
                    onClick = { onDisplayModeChange(SchedulerDisplayMode.CONDENSED) },
                    label = { Text("Ροές", fontSize = 11.sp) },
                    selected = displayMode == SchedulerDisplayMode.CONDENSED
                )

                FilterChip(
                    onClick = { onDisplayModeChange(SchedulerDisplayMode.HALF_HOURLY) },
                    label = { Text("30'", fontSize = 11.sp) },
                    selected = displayMode == SchedulerDisplayMode.HALF_HOURLY
                )

                FilterChip(
                    onClick = { onDisplayModeChange(SchedulerDisplayMode.HOURLY) },
                    label = { Text("60'", fontSize = 11.sp) },
                    selected = displayMode == SchedulerDisplayMode.HOURLY
                )
            }

            // Cell display mode toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Show:", fontSize = 12.sp)

                FilterChip(
                    onClick = { onCellDisplayModeChange(CellDisplayMode.SPOT_COUNT) },
                    label = { Text("#", fontSize = 11.sp) },
                    selected = cellDisplayMode == CellDisplayMode.SPOT_COUNT
                )

                FilterChip(
                    onClick = { onCellDisplayModeChange(CellDisplayMode.DURATION) },
                    label = { Text("Duration", fontSize = 11.sp) },
                    selected = cellDisplayMode == CellDisplayMode.DURATION
                )
            }

            // Lock button
            IconButton(onClick = onToggleLock) {
                Icon(
                    if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (isLocked) "Unlock" else "Lock",
                    tint = if (isLocked) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
        }
    }
}
