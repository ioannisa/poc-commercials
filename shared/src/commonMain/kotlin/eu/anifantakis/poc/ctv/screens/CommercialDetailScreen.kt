package eu.anifantakis.poc.ctv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.anifantakis.poc.ctv.grids.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * Commercial detail screen showing the list of commercials for a specific break and date
 * Similar to the second screenshot - "Break Console"
 * Now with reorder functionality using move up/down buttons
 */
@Composable
fun CommercialDetailScreen(
    breakId: Long,
    breakTime: String,
    date: StableDate,
    spotCount: Int,
    commercials: ImmutableList<CommercialItem>,
    onCommercialsReorder: (List<CommercialItem>) -> Unit,
    onBack: () -> Unit,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null
) {
    // Local mutable state for reordering - synced with parent
    // We treat the incoming ImmutableList as the initial value.
    // The internal state holds a List (which might be the ImmutableList or a new ArrayList after modification)
    var localCommercials by remember(commercials) { mutableStateOf<List<CommercialItem>>(commercials) }

    // Selected item for reordering
    var selectedIndex by remember { mutableStateOf(-1) }

    // Calculate totals from local list
    val totalDuration = localCommercials.sumOf { it.durationSeconds }
    val flowCount = localCommercials.count { it.flow == "ΡΟΗ" }
    val flowDuration = localCommercials.filter { it.flow == "ΡΟΗ" }.sumOf { it.durationSeconds }

    // Reorder functions
    fun moveUp(index: Int) {
        if (index > 0) {
            val newList = localCommercials.toMutableList()
            val item = newList.removeAt(index)
            newList.add(index - 1, item)
            localCommercials = newList
            selectedIndex = index - 1
            onCommercialsReorder(newList)
        }
    }

    fun moveDown(index: Int) {
        if (index < localCommercials.size - 1) {
            val newList = localCommercials.toMutableList()
            val item = newList.removeAt(index)
            newList.add(index + 1, item)
            localCommercials = newList
            selectedIndex = index + 1
            onCommercialsReorder(newList)
        }
    }

    // General reorder function for drag-and-drop
    fun reorderRow(fromIndex: Int, toIndex: Int) {
        if (fromIndex != toIndex && fromIndex in localCommercials.indices && toIndex in localCommercials.indices) {
            val newList = localCommercials.toMutableList()
            val item = newList.removeAt(fromIndex)
            newList.add(toIndex, item)
            localCommercials = newList
            selectedIndex = toIndex
            onCommercialsReorder(newList)
        }
    }

    // Greek day names
    val greekDays = mapOf(
        DayOfWeek.MONDAY to "Δευτέρα",
        DayOfWeek.TUESDAY to "Τρίτη",
        DayOfWeek.WEDNESDAY to "Τετάρτη",
        DayOfWeek.THURSDAY to "Πέμπτη",
        DayOfWeek.FRIDAY to "Παρασκευή",
        DayOfWeek.SATURDAY to "Σάββατο",
        DayOfWeek.SUNDAY to "Κυριακή"
    )

    val greekMonths = listOf(
        "ΙΑΝΟΥΑΡΙΟΥ", "ΦΕΒΡΟΥΑΡΙΟΥ", "ΜΑΡΤΙΟΥ", "ΑΠΡΙΛΙΟΥ",
        "ΜΑΙΟΥ", "ΙΟΥΝΙΟΥ", "ΙΟΥΛΙΟΥ", "ΑΥΓΟΥΣΤΟΥ",
        "ΣΕΠΤΕΜΒΡΙΟΥ", "ΟΚΤΩΒΡΙΟΥ", "ΝΟΕΜΒΡΙΟΥ", "ΔΕΚΕΜΒΡΙΟΥ"
    )

    // Create column definitions for the data grid
    val columns = remember(localCommercials) {
        listOf(
            // Reorder buttons column
            ColumnDef<CommercialItem>(
                id = "reorder",
                header = "↕",
                width = 70.dp,
                alignment = TextAlign.Center,
                headerAlignment = TextAlign.Center,
                extractor = { "" },
                sortable = false,
                cellContent = { item, _, _ ->
                    val index = localCommercials.indexOf(item)
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { moveUp(index) },
                            enabled = index > 0,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Move up",
                                modifier = Modifier.size(18.dp),
                                tint = if (index > 0) MaterialTheme.colorScheme.primary else Color.LightGray
                            )
                        }
                        IconButton(
                            onClick = { moveDown(index) },
                            enabled = index < localCommercials.size - 1,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Move down",
                                modifier = Modifier.size(18.dp),
                                tint = if (index < localCommercials.size - 1) MaterialTheme.colorScheme.primary else Color.LightGray
                            )
                        }
                    }
                }
            ),
            ColumnDef<CommercialItem>(
                id = "index",
                header = "ΑΡΙΘΜ.",
                width = 60.dp,
                alignment = TextAlign.Center,
                headerAlignment = TextAlign.Center,
                extractor = { "" },  // Will use row index
                sortable = false,
                cellContent = { item, _, _ ->
                    val index = localCommercials.indexOf(item) + 1
                    Text(
                        text = index.toString(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            ),
            ColumnDef(
                id = "clientCode",
                header = "Κωδ. Πελ.",
                width = 90.dp,
                alignment = TextAlign.Start,
                extractor = { it.clientCode }
            ),
            ColumnDef(
                id = "clientName",
                header = "Πελάτης",
                width = 220.dp,
                alignment = TextAlign.Start,
                extractor = { it.clientName }
            ),
            ColumnDef(
                id = "message",
                header = "Μήνυμα",
                width = 280.dp,
                alignment = TextAlign.Start,
                extractor = { it.message }
            ),
            ColumnDef(
                id = "duration",
                header = "sec",
                width = 60.dp,
                alignment = TextAlign.Center,
                headerAlignment = TextAlign.Center,
                extractor = { it.durationSeconds.toString() }
            ),
            ColumnDef(
                id = "type",
                header = "Τύπος",
                width = 160.dp,
                alignment = TextAlign.Start,
                extractor = { it.type }
            ),
            ColumnDef(
                id = "contract",
                header = "Σύμβαση",
                width = 80.dp,
                alignment = TextAlign.Center,
                headerAlignment = TextAlign.Center,
                extractor = { it.contract }
            ),
            ColumnDef(
                id = "flow",
                header = "ΡΟΗ",
                width = 60.dp,
                alignment = TextAlign.Center,
                headerAlignment = TextAlign.Center,
                extractor = { it.flow },
                cellBackground = { item ->
                    if (item.flow == "ΡΟΗ") Color(0xFFE8F5E9) else Color.Transparent
                }
            )
        ).toImmutableList()
    }

    val gridState = rememberEnhancedDataGridState(columns)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header section matching the screenshot
        DetailHeader(
            dayName = greekDays[date.value.dayOfWeek] ?: "",
            dayNumber = date.value.day,
            monthName = greekMonths[date.value.month.ordinal],
            year = date.value.year,
            breakTime = breakTime,
            totalSpots = localCommercials.size,
            flowSpots = flowCount,
            exceptSpots = localCommercials.size - flowCount,
            totalDuration = totalDuration,
            flowDuration = flowDuration,
            exceptDuration = totalDuration - flowDuration,
            onBack = onBack,
            onPrevious = onPrevious,
            onNext = onNext
        )

        HorizontalDivider(thickness = 2.dp, color = Color(0xFFBDBDBD))

        // Data grid with commercials
        EnhancedDataGrid(
            items = localCommercials.toImmutableList(),
            columns = columns,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            state = gridState,
            selectionMode = SelectionMode.SINGLE,
            showRowNumbers = false,
            rowHeight = 32.dp,
            headerHeight = 36.dp,
            stickyRows = StickyRowsConfig(
                stickyHeader = true,
                stickyFooter = false
            ),
            onRowClick = { item, index ->
                // Could show more detail
            },
            onRowDoubleClick = { item, index ->
                // Could open edit dialog
            },
            onRowReorder = { fromIndex, toIndex ->
                reorderRow(fromIndex, toIndex)
            },
            rowKey = { it.id },
            contextMenuItems = { item, rowIndex ->
                listOf(
                    // Edit action
                    ContextMenuEntry.Item(
                        label = "Edit Commercial",
                        icon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)) },
                        shortcut = "⌘E"
                    ) {
                        println("Edit: ${item.clientName} - ${item.message}")
                    },

                    // === SEPARATOR ===
                    ContextMenuEntry.Separator,

                    // Clipboard submenu
                    ContextMenuEntry.SubMenu(
                        label = "Clipboard",
                        icon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) },
                        items = listOf(
                            ContextMenuEntry.Item(
                                label = "Copy",
                                icon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) },
                                shortcut = "⌘C"
                            ) {
                                println("Copy: ${item.clientName}")
                            },
                            ContextMenuEntry.Item(
                                label = "Cut",
                                icon = { Icon(Icons.Default.ContentCut, null, modifier = Modifier.size(16.dp)) },
                                shortcut = "⌘X"
                            ) {
                                println("Cut: ${item.clientName}")
                            },
                            ContextMenuEntry.Item(
                                label = "Paste",
                                icon = { Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(16.dp)) },
                                shortcut = "⌘V"
                            ) {
                                println("Paste at index $rowIndex")
                            }
                        )
                    ),

                    // Move submenu
                    ContextMenuEntry.SubMenu(
                        label = "Move",
                        icon = { Icon(Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(16.dp)) },
                        items = listOf(
                            ContextMenuEntry.Item(
                                label = "Move Up",
                                icon = { Icon(Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(16.dp)) },
                                enabled = rowIndex > 0
                            ) {
                                if (rowIndex > 0) {
                                    reorderRow(rowIndex, rowIndex - 1)
                                }
                            },
                            ContextMenuEntry.Item(
                                label = "Move Down",
                                icon = { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp)) },
                                enabled = rowIndex < localCommercials.size - 1
                            ) {
                                if (rowIndex < localCommercials.size - 1) {
                                    reorderRow(rowIndex, rowIndex + 1)
                                }
                            },
                            ContextMenuEntry.Separator,
                            ContextMenuEntry.Item(
                                label = "Move to Top",
                                enabled = rowIndex > 0
                            ) {
                                if (rowIndex > 0) {
                                    reorderRow(rowIndex, 0)
                                }
                            },
                            ContextMenuEntry.Item(
                                label = "Move to Bottom",
                                enabled = rowIndex < localCommercials.size - 1
                            ) {
                                if (rowIndex < localCommercials.size - 1) {
                                    reorderRow(rowIndex, localCommercials.size - 1)
                                }
                            }
                        )
                    ),

                    // === SEPARATOR ===
                    ContextMenuEntry.Separator,

                    // Delete action
                    ContextMenuEntry.Item(
                        label = "Delete",
                        icon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp)) },
                        shortcut = "⌫"
                    ) {
                        println("Delete: ${item.clientName} at index $rowIndex")
                    },

                    // === SEPARATOR ===
                    ContextMenuEntry.Separator,

                    // More options
                    ContextMenuEntry.SubMenu(
                        label = "More",
                        icon = { Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(16.dp)) },
                        items = listOf(
                            ContextMenuEntry.Item(
                                label = "Preview",
                                icon = { Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp)) }
                            ) {
                                println("Preview: ${item.message}")
                            },
                            ContextMenuEntry.Item(
                                label = "View History",
                                icon = { Icon(Icons.Default.History, null, modifier = Modifier.size(16.dp)) }
                            ) {
                                println("History for: ${item.clientName}")
                            },
                            ContextMenuEntry.Separator,
                            ContextMenuEntry.Item(
                                label = "Details",
                                icon = { Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp)) }
                            ) {
                                println("Details: Client=${item.clientCode}, Duration=${item.durationSeconds}s, Contract=${item.contract}")
                            }
                        )
                    )
                )
            }
        )
    }
}

@Composable
private fun DetailHeader(
    dayName: String,
    dayNumber: Int,
    monthName: String,
    year: Int,
    breakTime: String,
    totalSpots: Int,
    flowSpots: Int,
    exceptSpots: Int,
    totalDuration: Int,
    flowDuration: Int,
    exceptDuration: Int,
    onBack: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF5F5F5)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Top row with date info and navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Left side - Date and break info
                Column {
                    // Date display matching screenshot style
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }

                        Column {
                            Text(
                                text = "$dayName - $dayNumber $monthName $year",
                                color = Color(0xFF1565C0),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Ώρα Διαλείμματος",
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = breakTime,
                                    color = Color(0xFFC62828),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Stats row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        StatColumn(
                            label = "",
                            value1 = "ΟΛΑ",
                            value2 = "ΡΟΗΣ",
                            value3 = "ΕΞΑΙΡ."
                        )
                        StatColumn(
                            label = "Σύνολο Spots",
                            value1 = totalSpots.toString(),
                            value2 = flowSpots.toString(),
                            value3 = exceptSpots.toString()
                        )
                        StatColumn(
                            label = "Συνολική Διάρκεια",
                            value1 = formatDuration(totalDuration),
                            value2 = formatDuration(flowDuration),
                            value3 = formatDuration(exceptDuration)
                        )
                    }
                }

                // Right side - Show name and navigation
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "MOVIE TIME",
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onPrevious?.invoke() },
                            enabled = onPrevious != null
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Προηγούμενο")
                        }

                        OutlinedButton(
                            onClick = { onNext?.invoke() },
                            enabled = onNext != null
                        ) {
                            Text("Επόμενο")
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatColumn(
    label: String,
    value1: String,
    value2: String,
    value3: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                fontSize = 10.sp,
                color = Color.Gray
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = value1,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.width(50.dp),
                textAlign = TextAlign.Center
            )
            Text(
                text = value2,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.width(50.dp),
                textAlign = TextAlign.Center
            )
            Text(
                text = value3,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.width(50.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}
