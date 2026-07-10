package eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail

import eu.anifantakis.commercials.core.presentation.string_resources.LocalLanguage
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.anifantakis.commercials.core.domain.auth.UserSession
import eu.anifantakis.commercials.core.presentation.grids.ColumnDef
import eu.anifantakis.commercials.core.presentation.grids.CommercialItem
import eu.anifantakis.commercials.core.presentation.grids.ContextMenuEntry
import eu.anifantakis.commercials.core.presentation.grids.EnhancedDataGrid
import eu.anifantakis.commercials.core.presentation.grids.FLOW_ROH
import eu.anifantakis.commercials.core.presentation.grids.SelectionMode
import eu.anifantakis.commercials.core.presentation.grids.StableDate
import eu.anifantakis.commercials.core.presentation.grids.StickyRowsConfig
import eu.anifantakis.commercials.core.presentation.grids.formatDuration
import eu.anifantakis.commercials.core.presentation.grids.gridPalette
import eu.anifantakis.commercials.core.presentation.grids.rememberEnhancedDataGridState
import eu.anifantakis.commercials.reports.ReportDataFactory
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.models.ReportConfig
import eu.anifantakis.commercials.reports.print
import eu.anifantakis.commercials.reports.toReportPayload
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject

/**
 * Break Console entry point: own ViewModel (per-screen), the cell's
 * commercials observed from the shared ScheduleCellsStore.
 * [onNavigateToBreak] re-targets the screen to a sibling break of the same
 * day - the Προηγούμενο/Επόμενο paging of the legacy Break Console.
 */
@Composable
fun CommercialDetailScreenRoot(
    breakId: Long,
    breakTime: String,
    date: LocalDate,
    spotCount: Int,
    viewModel: CommercialDetailViewModel,
    onBack: () -> Unit,
    onNavigateToBreak: (breakId: Long, breakTime: String, spotCount: Int) -> Unit,
) {
    val previous = viewModel.state.previousBreak
    val next = viewModel.state.nextBreak
    val goToBreak: (BreakRef) -> Unit = { onNavigateToBreak(it.breakId, it.label, it.spotCount) }
    CommercialDetailScreen(
        breakId = breakId,
        breakTime = breakTime,
        date = StableDate(date),
        spotCount = spotCount,
        commercials = viewModel.state.commercials,
        programName = viewModel.state.programName,
        onCommercialsReorder = { reordered ->
            viewModel.onAction(CommercialDetailIntent.Reorder(reordered.map { it.id }))
        },
        onNavIntent = { navIntent ->
            when (navIntent) {
                CommercialDetailScreenNavIntent.OnBack -> onBack()
            }
        },
        onPrevious = previous?.let { p -> { goToBreak(p) } },
        onNext = next?.let { n -> { goToBreak(n) } },
    )
}

/**
 * Navigation-only actions of the detail screen — always via this single
 * parameter (predictable shape). Not a ViewModel [CommercialDetailIntent]
 * (reorder is that); prev/next paging rides the dedicated nullable
 * callbacks the header buttons already expose.
 */
private sealed interface CommercialDetailScreenNavIntent {
    data object OnBack : CommercialDetailScreenNavIntent
}

/**
 * Commercial detail screen showing the list of commercials for a specific break and date
 * Similar to the second screenshot - "Break Console"
 * Now with reorder functionality using move up/down buttons
 */
@Composable
private fun CommercialDetailScreen(
    breakId: Long,
    breakTime: String,
    date: StableDate,
    spotCount: Int,
    commercials: ImmutableList<CommercialItem>,
    programName: String?,
    onCommercialsReorder: (List<CommercialItem>) -> Unit,
    onNavIntent: (CommercialDetailScreenNavIntent) -> Unit,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null
) {
    // View-only roles can browse and print, but not reorder/edit
    val authSession = koinInject<UserSession>()
    val canEdit = authSession.role.canEdit

    // Local mutable state for reordering - synced with parent
    // We treat the incoming ImmutableList as the initial value.
    // The internal state holds a List (which might be the ImmutableList or a new ArrayList after modification)
    var localCommercials by remember(commercials) { mutableStateOf<List<CommercialItem>>(commercials) }

    // Selected item for reordering
    var selectedIndex by remember { mutableStateOf(-1) }

    // Print this break's program flow (same report the scheduler popups print)
    val reportScope = rememberCoroutineScope()
    val reportService = koinInject<ReportService>()

    fun printBreak() {
        reportScope.launch {
            val data = ReportDataFactory.createBreakProgramFlowData(
                date = date.value,
                breakTimeLabel = breakTime,
                commercials = localCommercials
            )
            if (data.items.isNotEmpty()) {
                reportService.print(data.toReportPayload(ReportConfig()))
            }
        }
    }

    // Calculate totals from local list
    val totalDuration = localCommercials.sumOf { it.durationSeconds }
    val flowCount = localCommercials.count { it.flow == FLOW_ROH }
    val flowDuration = localCommercials.filter { it.flow == FLOW_ROH }.sumOf { it.durationSeconds }

    // Reorder functions
    fun moveUp(index: Int) {
        if (!canEdit) return
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
        if (!canEdit) return
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
        if (!canEdit) return
        if (fromIndex != toIndex && fromIndex in localCommercials.indices && toIndex in localCommercials.indices) {
            val newList = localCommercials.toMutableList()
            val item = newList.removeAt(fromIndex)
            newList.add(toIndex, item)
            localCommercials = newList
            selectedIndex = toIndex
            onCommercialsReorder(newList)
        }
    }

    // Localized day names (Strings[] — recompose on language switch)
    val dayNames = mapOf(
        DayOfWeek.MONDAY to Strings[StringKey.DAY_MONDAY],
        DayOfWeek.TUESDAY to Strings[StringKey.DAY_TUESDAY],
        DayOfWeek.WEDNESDAY to Strings[StringKey.DAY_WEDNESDAY],
        DayOfWeek.THURSDAY to Strings[StringKey.DAY_THURSDAY],
        DayOfWeek.FRIDAY to Strings[StringKey.DAY_FRIDAY],
        DayOfWeek.SATURDAY to Strings[StringKey.DAY_SATURDAY],
        DayOfWeek.SUNDAY to Strings[StringKey.DAY_SUNDAY]
    )

    // Genitive month names for the day header (localized)
    val monthOfNames = listOf(
        Strings[StringKey.MONTH_OF_JANUARY], Strings[StringKey.MONTH_OF_FEBRUARY],
        Strings[StringKey.MONTH_OF_MARCH], Strings[StringKey.MONTH_OF_APRIL],
        Strings[StringKey.MONTH_OF_MAY], Strings[StringKey.MONTH_OF_JUNE],
        Strings[StringKey.MONTH_OF_JULY], Strings[StringKey.MONTH_OF_AUGUST],
        Strings[StringKey.MONTH_OF_SEPTEMBER], Strings[StringKey.MONTH_OF_OCTOBER],
        Strings[StringKey.MONTH_OF_NOVEMBER], Strings[StringKey.MONTH_OF_DECEMBER]
    )

    // Create column definitions for the data grid
    // Captured here: column lambdas ((T) -> Color) are not composable, so the
    // theme-resolved palette must be read in composition and closed over.
    // Keyed into the remember so a live theme OR LANGUAGE switch rebuilds
    // the columns (headers resolve via .localized() inside the non-composable
    // remember block).
    val palette = gridPalette()
    val language = LocalLanguage.current
    val columns = remember(localCommercials, palette, language) {
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
                                AppIcons.keyboardArrowUp,
                                contentDescription = Strings[StringKey.DETAIL_CD_MOVE_UP],
                                modifier = Modifier.size(18.dp),
                                tint = if (index > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        }
                        IconButton(
                            onClick = { moveDown(index) },
                            enabled = index < localCommercials.size - 1,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                AppIcons.keyboardArrowDown,
                                contentDescription = Strings[StringKey.DETAIL_CD_MOVE_DOWN],
                                modifier = Modifier.size(18.dp),
                                tint = if (index < localCommercials.size - 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            ),
            ColumnDef<CommercialItem>(
                id = "index",
                header = StringKey.DETAIL_COL_NUMBER.localized(),
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
                header = StringKey.DETAIL_COL_CUSTOMER_CODE.localized(),
                width = 90.dp,
                alignment = TextAlign.Start,
                extractor = { it.clientCode }
            ),
            ColumnDef(
                id = "clientName",
                header = StringKey.DETAIL_COL_CUSTOMER.localized(),
                width = 220.dp,
                alignment = TextAlign.Start,
                extractor = { it.clientName }
            ),
            ColumnDef(
                id = "message",
                header = StringKey.DETAIL_COL_MESSAGE.localized(),
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
                header = StringKey.DETAIL_COL_TYPE.localized(),
                width = 200.dp,
                alignment = TextAlign.Start,
                // The legacy Break Console shows the contract line's SALES
                // item here ('Διαφ. TV Κρήτη Σ73.002', 'Διαφημίσεις
                // τηλεόρασης Δ Ω Ρ Α' - the item name carries the gift
                // marker). Programme type is the fallback for spots the ERP
                // never covered, with the marker appended for gifts.
                extractor = { it.salesItem ?: if (it.isGift) "${it.type} ΔΩΡΑ" else it.type }
            ),
            ColumnDef(
                id = "contract",
                header = StringKey.DETAIL_COL_CONTRACT.localized(),
                width = 80.dp,
                alignment = TextAlign.Center,
                headerAlignment = TextAlign.Center,
                // Always the contract NUMBER, like the legacy console - the
                // gift marker lives in the Τύπος column, not here.
                extractor = { it.contract }
            ),
            ColumnDef(
                id = "flow",
                header = StringKey.DETAIL_COL_FLOW.localized(),
                width = 60.dp,
                alignment = TextAlign.Center,
                headerAlignment = TextAlign.Center,
                extractor = { it.flow },
                cellBackground = { item ->
                    if (item.flow == FLOW_ROH) palette.positiveValue.copy(alpha = 0.15f) else Color.Transparent
                }
            )
        ).filter { canEdit || it.id != "reorder" }.toImmutableList()
    }

    val gridState = rememberEnhancedDataGridState(columns)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header section matching the screenshot
        DetailHeader(
            dayName = dayNames[date.value.dayOfWeek] ?: "",
            dayNumber = date.value.day,
            monthName = monthOfNames[date.value.month.ordinal],
            year = date.value.year,
            breakTime = breakTime,
            showName = programName,
            totalSpots = localCommercials.size,
            flowSpots = flowCount,
            exceptSpots = localCommercials.size - flowCount,
            totalDuration = totalDuration,
            flowDuration = flowDuration,
            exceptDuration = totalDuration - flowDuration,
            onBack = { onNavIntent(CommercialDetailScreenNavIntent.OnBack) },
            onPrevious = onPrevious,
            onNext = onNext,
            onPrint = { printBreak() }
        )

        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant)

        // Data grid with commercials
        EnhancedDataGrid(
            summaryLabel = Strings[StringKey.TIMETABLE_TOTAL_SHORT],
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
                    // Print this break's program flow
                    ContextMenuEntry.Item(
                        label = StringKey.TIMETABLE_MENU_PRINT_BREAK.localized(),
                        icon = { Icon(AppIcons.print, null, modifier = Modifier.size(16.dp)) },
                        enabled = localCommercials.isNotEmpty()
                    ) {
                        printBreak()
                    },

                    // === SEPARATOR ===
                    ContextMenuEntry.Separator,

                    // Edit action
                    ContextMenuEntry.Item(
                        label = StringKey.DETAIL_MENU_EDIT_COMMERCIAL.localized(),
                        icon = { Icon(AppIcons.edit, null, modifier = Modifier.size(16.dp)) },
                        shortcut = "⌘E",
                        enabled = canEdit
                    ) {
                        println("Edit: ${item.clientName} - ${item.message}")
                    },

                    // === SEPARATOR ===
                    ContextMenuEntry.Separator,

                    // Clipboard submenu
                    ContextMenuEntry.SubMenu(
                        label = StringKey.DETAIL_MENU_CLIPBOARD.localized(),
                        icon = { Icon(AppIcons.contentCopy, null, modifier = Modifier.size(16.dp)) },
                        enabled = canEdit,
                        items = listOf(
                            ContextMenuEntry.Item(
                                label = StringKey.COMMON_COPY.localized(),
                                icon = { Icon(AppIcons.contentCopy, null, modifier = Modifier.size(16.dp)) },
                                shortcut = "⌘C"
                            ) {
                                println("Copy: ${item.clientName}")
                            },
                            ContextMenuEntry.Item(
                                label = StringKey.COMMON_CUT.localized(),
                                icon = { Icon(AppIcons.contentCut, null, modifier = Modifier.size(16.dp)) },
                                shortcut = "⌘X"
                            ) {
                                println("Cut: ${item.clientName}")
                            },
                            ContextMenuEntry.Item(
                                label = StringKey.COMMON_PASTE.localized(),
                                icon = { Icon(AppIcons.contentPaste, null, modifier = Modifier.size(16.dp)) },
                                shortcut = "⌘V"
                            ) {
                                println("Paste at index $rowIndex")
                            }
                        )
                    ),

                    // Move submenu
                    ContextMenuEntry.SubMenu(
                        label = StringKey.DETAIL_MENU_MOVE.localized(),
                        icon = { Icon(AppIcons.keyboardArrowUp, null, modifier = Modifier.size(16.dp)) },
                        enabled = canEdit,
                        items = listOf(
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_MOVE_UP.localized(),
                                icon = { Icon(AppIcons.keyboardArrowUp, null, modifier = Modifier.size(16.dp)) },
                                enabled = rowIndex > 0
                            ) {
                                if (rowIndex > 0) {
                                    reorderRow(rowIndex, rowIndex - 1)
                                }
                            },
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_MOVE_DOWN.localized(),
                                icon = { Icon(AppIcons.keyboardArrowDown, null, modifier = Modifier.size(16.dp)) },
                                enabled = rowIndex < localCommercials.size - 1
                            ) {
                                if (rowIndex < localCommercials.size - 1) {
                                    reorderRow(rowIndex, rowIndex + 1)
                                }
                            },
                            ContextMenuEntry.Separator,
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_MOVE_TOP.localized(),
                                enabled = rowIndex > 0
                            ) {
                                if (rowIndex > 0) {
                                    reorderRow(rowIndex, 0)
                                }
                            },
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_MOVE_BOTTOM.localized(),
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
                        label = StringKey.COMMON_DELETE.localized(),
                        icon = { Icon(AppIcons.delete, null, modifier = Modifier.size(16.dp)) },
                        shortcut = "⌫",
                        enabled = canEdit
                    ) {
                        println("Delete: ${item.clientName} at index $rowIndex")
                    },

                    // === SEPARATOR ===
                    ContextMenuEntry.Separator,

                    // More options
                    ContextMenuEntry.SubMenu(
                        label = StringKey.DETAIL_MENU_MORE.localized(),
                        icon = { Icon(AppIcons.moreVert, null, modifier = Modifier.size(16.dp)) },
                        items = listOf(
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_PREVIEW.localized(),
                                icon = { Icon(AppIcons.playArrow, null, modifier = Modifier.size(16.dp)) }
                            ) {
                                println("Preview: ${item.message}")
                            },
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_HISTORY.localized(),
                                icon = { Icon(AppIcons.history, null, modifier = Modifier.size(16.dp)) }
                            ) {
                                println("History for: ${item.clientName}")
                            },
                            ContextMenuEntry.Separator,
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_DETAILS.localized(),
                                icon = { Icon(AppIcons.info, null, modifier = Modifier.size(16.dp)) }
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
    showName: String?,
    totalSpots: Int,
    flowSpots: Int,
    exceptSpots: Int,
    totalDuration: Int,
    flowDuration: Int,
    exceptDuration: Int,
    onBack: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onPrint: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
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
                                AppIcons.arrowBack,
                                contentDescription = Strings[StringKey.COMMON_BACK]
                            )
                        }

                        Column {
                            AppText(
                                "$dayName - $dayNumber $monthName $year",
                                AppTextStyle.ITEM_TITLE,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AppText(
                                    Strings[StringKey.DETAIL_BREAK_TIME],
                                    AppTextStyle.NOTE,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                AppText(
                                    breakTime,
                                    AppTextStyle.STAT_VALUE,
                                    color = MaterialTheme.colorScheme.error,
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
                            value1 = Strings[StringKey.DETAIL_TOTAL_ALL],
                            value2 = Strings[StringKey.DETAIL_TOTAL_FLOW],
                            value3 = Strings[StringKey.DETAIL_TOTAL_EXCLUDED]
                        )
                        StatColumn(
                            label = Strings[StringKey.DETAIL_TOTAL_SPOTS],
                            value1 = totalSpots.toString(),
                            value2 = flowSpots.toString(),
                            value3 = exceptSpots.toString()
                        )
                        StatColumn(
                            label = Strings[StringKey.DETAIL_TOTAL_DURATION],
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
                    // The programme airing in this break, when one is on record
                    // (migrated stations carry it; demo data has none).
                    if (!showName.isNullOrBlank()) {
                        AppText(
                            showName,
                            AppTextStyle.ITEM_TITLE,
                            color = gridPalette().positiveValue,
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onPrint != null) {
                            OutlinedButton(onClick = onPrint) {
                                Icon(
                                    AppIcons.print,
                                    contentDescription = Strings[StringKey.DETAIL_CD_PRINT_BREAK],
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(Strings[StringKey.DETAIL_PRINT])
                            }
                        }

                        OutlinedButton(
                            onClick = { onPrevious?.invoke() },
                            enabled = onPrevious != null
                        ) {
                            Icon(
                                AppIcons.arrowBack,
                                contentDescription = Strings[StringKey.DETAIL_PREVIOUS],
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(Strings[StringKey.DETAIL_PREVIOUS])
                        }

                        OutlinedButton(
                            onClick = { onNext?.invoke() },
                            enabled = onNext != null
                        ) {
                            Text(Strings[StringKey.DETAIL_NEXT])
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                AppIcons.arrowForward,
                                contentDescription = Strings[StringKey.DETAIL_NEXT],
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
            AppText(label, AppTextStyle.STAT_LABEL)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppText(
                value1,
                AppTextStyle.TABLE_CELL_STRONG,
                modifier = Modifier.width(50.dp),
                textAlign = TextAlign.Center
            )
            AppText(
                value2,
                AppTextStyle.TABLE_CELL_STRONG,
                modifier = Modifier.width(50.dp),
                textAlign = TextAlign.Center
            )
            AppText(
                value3,
                AppTextStyle.TABLE_CELL_STRONG,
                modifier = Modifier.width(50.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}
