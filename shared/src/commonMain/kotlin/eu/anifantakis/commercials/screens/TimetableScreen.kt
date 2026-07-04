package eu.anifantakis.commercials.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material.icons.automirrored.filled.Logout
import eu.anifantakis.commercials.auth.AuthSession
import eu.anifantakis.commercials.data.SampleData
import eu.anifantakis.commercials.grids.*
import eu.anifantakis.commercials.prefs.UserPreferences
import eu.anifantakis.commercials.reports.ReportDataFactory
import eu.anifantakis.commercials.reports.ReportPayload
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.models.ReportConfig
import eu.anifantakis.commercials.reports.print
import eu.anifantakis.commercials.reports.toReportPayload
import eu.anifantakis.commercials.reports.ui.ReportToolbar
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
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
    onCellClick: (breakId: Long, breakTime: String, date: LocalDate, spotCount: Int) -> Unit,
    onLogout: () -> Unit,
    onPreferences: () -> Unit
) {
    // View-only roles (Report Viewer / Customer Viewer) see everything their
    // data allows but cannot modify it. The role is PER STATION, so reading
    // revision makes edit gating react to station switches too.
    val authSession = koinInject<AuthSession>()
    @Suppress("UNUSED_EXPRESSION") authSession.revision
    val canEdit = authSession.role.canEdit

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

    // Cells show spot COUNT or summed spot TIME (legacy "spots count /
    // spots times" popup option) - persisted in KSafe via UserPreferences.
    val prefs = koinInject<UserPreferences>()

    // Report printing from popup menus (day header, break header, cell)
    val reportScope = rememberCoroutineScope()
    val reportService = koinInject<ReportService>()

    fun printDay(date: LocalDate) {
        reportScope.launch {
            val data = ReportDataFactory.createProgramFlowData(date, breaks, cellData)
            if (data.items.isNotEmpty()) {
                reportService.print(data.toReportPayload(ReportConfig()))
            }
        }
    }

    fun printBreak(breakSlot: BreakSlot, date: LocalDate) {
        reportScope.launch {
            val commercials = cellData[SchedulerKey(breakSlot.id, date)]?.commercials ?: return@launch
            val data = ReportDataFactory.createBreakProgramFlowData(
                date = date,
                breakTimeLabel = formatTime(breakSlot.time.hour, breakSlot.time.minute),
                commercials = commercials
            )
            if (data.items.isNotEmpty()) {
                reportService.print(data.toReportPayload(ReportConfig()))
            }
        }
    }

    fun printBreakForMonth(breakSlot: BreakSlot) {
        reportScope.launch {
            val payloads: List<ReportPayload> = ReportDataFactory
                .createMonthProgramFlowData(year, month, listOf(breakSlot), cellData)
                .map { it.toReportPayload(ReportConfig()) }
            if (payloads.isNotEmpty()) {
                reportService.print(payloads)
            }
        }
    }

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
            canEdit = canEdit,
            onLogout = onLogout,
            onPreferences = onPreferences,
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
            showTimes = prefs.showSpotTimes,
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
            onAddSpot = if (!canEdit) null else { breakSlot, date ->
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
            onDeleteSpot = if (!canEdit) null else { breakSlot, date ->
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

                buildList {
                    // Open/View details
                    add(ContextMenuEntry.Item(
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
                    })

                    // Print the whole day this cell belongs to
                    add(ContextMenuEntry.Item(
                        label = "Print Day ${dayMenuLabel(date)}",
                        icon = { Icon(Icons.Default.Print, null, modifier = Modifier.size(16.dp)) },
                        enabled = cellData.any { it.key.date == date && it.value.spotCount > 0 }
                    ) {
                        printDay(date)
                    })

                    // Print this break's commercials
                    add(ContextMenuEntry.Item(
                        label = "Print Break",
                        icon = { Icon(Icons.Default.Print, null, modifier = Modifier.size(16.dp)) },
                        enabled = spotCount > 0
                    ) {
                        printBreak(breakSlot, date)
                    })

                    // Legacy popup option: cells show spot counts or the
                    // summed spot times (05:42 for 342s). Persisted.
                    add(ContextMenuEntry.Separator)
                    add(ContextMenuEntry.Item(
                        label = if (prefs.showSpotTimes) "Show Spot Counts" else "Show Spot Times",
                        icon = {
                            Icon(
                                if (prefs.showSpotTimes) Icons.Default.Numbers else Icons.Default.Timer,
                                null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    ) {
                        prefs.showSpotTimes = !prefs.showSpotTimes
                    })

                    // Editing actions - Normal User only; viewer roles get a
                    // read-and-print menu
                    if (canEdit) {
                        add(ContextMenuEntry.Separator)

                        // Edit submenu
                        add(ContextMenuEntry.SubMenu(
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
                        ))

                        add(ContextMenuEntry.Separator)

                        // Clipboard operations
                        add(ContextMenuEntry.Item(
                            label = "Copy",
                            icon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) },
                            shortcut = "⌘C",
                            enabled = spotCount > 0
                        ) {
                            println("Copy: ${breakSlot.id} on $date")
                        })
                        add(ContextMenuEntry.Item(
                            label = "Cut",
                            icon = { Icon(Icons.Default.ContentCut, null, modifier = Modifier.size(16.dp)) },
                            shortcut = "⌘X",
                            enabled = spotCount > 0
                        ) {
                            println("Cut: ${breakSlot.id} on $date")
                        })
                        add(ContextMenuEntry.Item(
                            label = "Paste",
                            icon = { Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(16.dp)) },
                            shortcut = "⌘V"
                        ) {
                            println("Paste at: ${breakSlot.id} on $date")
                        })
                    }

                    add(ContextMenuEntry.Separator)

                    // More options submenu
                    add(ContextMenuEntry.SubMenu(
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
                    ))
                }
            },
            dayHeaderContextMenuItems = { date ->
                listOf(
                    ContextMenuEntry.Item(
                        label = "Print Day ${dayMenuLabel(date)}",
                        icon = { Icon(Icons.Default.Print, null, modifier = Modifier.size(16.dp)) },
                        enabled = cellData.any { it.key.date == date && it.value.spotCount > 0 }
                    ) {
                        printDay(date)
                    }
                )
            },
            breakHeaderContextMenuItems = { breakSlot ->
                val label = formatTime(breakSlot.time.hour, breakSlot.time.minute)

                listOf(
                    ContextMenuEntry.Item(
                        label = "Print Break $label (Entire Month)",
                        icon = { Icon(Icons.Default.Print, null, modifier = Modifier.size(16.dp)) }
                    ) {
                        printBreakForMonth(breakSlot)
                    }
                )
            }
        )
    }
}

/** Menu label for a day, e.g. "05/12". */
private fun dayMenuLabel(date: LocalDate): String =
    "${date.day.toString().padStart(2, '0')}/${date.monthNumber.toString().padStart(2, '0')}"

/**
 * Shows which station's data is on screen. With a single grant it's a plain
 * label; with several it becomes a dropdown - selecting one switches the
 * whole app to that station's schema (data refetch + role re-evaluation are
 * driven by the session revision bump in [AuthSession.selectStation]).
 */
@Composable
private fun StationSelector(authSession: AuthSession) {
    val current = authSession.selectedStation ?: return
    var expanded by remember { mutableStateOf(false) }

    Box {
        if (authSession.stations.size > 1) {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = current.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Switch station",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                authSession.stations.forEach { station ->
                    DropdownMenuItem(
                        text = { Text(station.name) },
                        leadingIcon = {
                            if (station.id == current.id) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                        },
                        onClick = {
                            expanded = false
                            authSession.selectStation(station.id)
                        }
                    )
                }
            }
        } else {
            Text(
                text = current.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun KeyboardEnabledHeader(
    year: Int,
    month: Int,
    monthName: String,
    breaks: ImmutableList<BreakSlot>,
    cellData: ImmutableMap<SchedulerKey, SchedulerCellData>,
    canEdit: Boolean,
    onLogout: () -> Unit,
    onPreferences: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
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
                        text = if (canEdit) "Arrows: Navigate | Enter: Open | A: Add | D: Delete"
                        else "Arrows: Navigate | Enter: Open (view only)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Click grid to focus, then use keyboard",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Cells: spot count <-> summed spot time (persisted; the
                // icon shows the mode you'll switch TO)
                val prefs = koinInject<UserPreferences>()
                IconButton(onClick = { prefs.showSpotTimes = !prefs.showSpotTimes }) {
                    Icon(
                        if (prefs.showSpotTimes) Icons.Default.Numbers else Icons.Default.Timer,
                        contentDescription = if (prefs.showSpotTimes) "Show spot counts" else "Show spot times"
                    )
                }

                // Station switcher (dropdown only when the user can access
                // more than one station)
                val authSession = koinInject<AuthSession>()
                @Suppress("UNUSED_EXPRESSION") authSession.revision
                StationSelector(authSession)

                // Email a party their schedule (staff action). One email,
                // one table per spot; the dialog has its own search and
                // year/month drill-down, independent of the shown month.
                if (canEdit) {
                    var showEmail by remember { mutableStateOf(false) }
                    IconButton(onClick = { showEmail = true }) {
                        Icon(Icons.Default.Email, contentDescription = "Email customer schedule")
                    }
                    if (showEmail) {
                        SendScheduleEmailDialog(onDismiss = { showEmail = false })
                    }
                }

                // Logged-in user: clicking the badge opens the account menu
                // (change password / recovery codes, or user management for
                // the super admin)
                AccountBadge(authSession)
                IconButton(onClick = onPreferences) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Preferences"
                    )
                }
                IconButton(onClick = onLogout) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        contentDescription = "Logout",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Report toolbar - Preview/Print/Export cover the entire month
            ReportToolbar(
                year = year,
                month = month,
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
        color = MaterialTheme.colorScheme.surfaceVariant,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
        color = MaterialTheme.colorScheme.surfaceVariant,
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
                    tint = if (isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The logged-in user badge; clicking it opens the account menu. Regular
 * users get self-service actions (change password, recovery codes); the
 * super admin gets user management instead - its password and recovery are
 * managed in stations.yaml, not through the API.
 */
@Composable
private fun AccountBadge(authSession: AuthSession) {
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = authSession.displayName,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (authSession.isAdmin) "Super Administrator" else authSession.role.label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
