package eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail

import eu.anifantakis.commercials.feature.timetable.presentation.screens.reportToolbarLabels
import eu.anifantakis.commercials.feature.timetable.presentation.screens.reportToolbarMetrics
import eu.anifantakis.commercials.reports.ui.ReportToolbar
import eu.anifantakis.commercials.core.presentation.string_resources.LocalLanguage
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconSize
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalTime
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import eu.anifantakis.commercials.grids.ColumnDef
import eu.anifantakis.commercials.grids.CommercialItem
import eu.anifantakis.commercials.grids.ContextMenuEntry
import eu.anifantakis.commercials.grids.EnhancedDataGrid
import eu.anifantakis.commercials.grids.FLOW_ROH
import eu.anifantakis.commercials.grids.SelectionMode
import eu.anifantakis.commercials.grids.StickyRowsConfig
import eu.anifantakis.commercials.grids.formatDuration
import eu.anifantakis.commercials.grids.gridPalette
import eu.anifantakis.commercials.grids.rememberEnhancedDataGridState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import androidx.compose.ui.tooling.preview.Preview

/**
 * Break Console entry point: own ViewModel (per-screen), the cell's
 * commercials observed from the shared ScheduleCellsStore.
 * [onNavigateToBreak] re-targets the screen to a sibling break of the same
 * day - the Προηγούμενο/Επόμενο paging of the legacy Break Console.
 */
@Composable
fun CommercialDetailScreenRoot(
    viewModel: CommercialDetailViewModel,
    onBack: () -> Unit,
    onNavigateToBreak: (time: LocalTime) -> Unit,
) {
    CommercialDetailScreen(
        state = viewModel.state,
        onIntent = viewModel::onAction,
        onNavIntent = { navIntent ->
            when (navIntent) {
                CommercialDetailScreenNavIntent.OnBack -> onBack()
                is CommercialDetailScreenNavIntent.OnGoToBreak ->
                    onNavigateToBreak(navIntent.target.time)
            }
        },
    )
}

/**
 * Navigation-only actions of the detail screen — always via this single
 * parameter (predictable shape). Not ViewModel [CommercialDetailIntent]s: the
 * VM computes WHICH break is next (it is state), but going there is navigation.
 */
private sealed interface CommercialDetailScreenNavIntent {
    data object OnBack : CommercialDetailScreenNavIntent
    /** Προηγούμενο/Επόμενο - page to a neighbouring occupied break. */
    data class OnGoToBreak(val target: BreakRef) : CommercialDetailScreenNavIntent
}

/**
 * Commercial detail screen showing the list of commercials for a specific break and date
 * Similar to the second screenshot - "Break Console"
 * Now with reorder functionality using move up/down buttons
 */
@Composable
private fun CommercialDetailScreen(
    state: CommercialDetailState,
    onIntent: (CommercialDetailIntent) -> Unit,
    onNavIntent: (CommercialDetailScreenNavIntent) -> Unit,
) {
    // Everything below RENDERS state. The list is the shared store's (the VM
    // observes it), reordering is an Intent, and the header stats arrived
    // pre-computed - no second copy of the truth lives here.
    val commercials = state.commercials
    val canEdit = state.canEdit
    val date = state.date

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
    //
    // gridScale is a key too: EnhancedDataGrid scales its own type and row
    // heights, but column WIDTHS come from these ColumnDefs - they have to be
    // rebuilt (wider) so the bigger text is not merely ellipsized.
    val palette = gridPalette()
    val language = LocalLanguage.current
    val gridScale = AppTheme.fontSizeStep.factor
    val columns = remember(commercials, palette, language, gridScale, canEdit) {
        listOf(
            // Reorder buttons column
            ColumnDef<CommercialItem>(
                id = "reorder",
                header = "↕",
                width = 70.dp * gridScale,
                alignment = TextAlign.Center,
                headerAlignment = TextAlign.Center,
                extractor = { "" },
                sortable = false,
                cellContent = { item, _, _ ->
                    val index = commercials.indexOf(item)
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 24dp is grid-cell geometry: two buttons must fit the
                        // dense reorder column, so the touch floor is opted out.
                        AppIconButton(
                            label = Strings[StringKey.DETAIL_CD_MOVE_UP],
                            icon = AppIcons.keyboardArrowUp,
                            onClick = { onIntent(CommercialDetailIntent.MoveRow(index, index - 1)) },
                            enabled = index > 0,
                            modifier = Modifier.size(24.dp),
                            size = AppIconSize.SMALL,
                            tint = if (index > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                        AppIconButton(
                            label = Strings[StringKey.DETAIL_CD_MOVE_DOWN],
                            icon = AppIcons.keyboardArrowDown,
                            onClick = { onIntent(CommercialDetailIntent.MoveRow(index, index + 1)) },
                            enabled = index < commercials.size - 1,
                            modifier = Modifier.size(24.dp),
                            size = AppIconSize.SMALL,
                            tint = if (index < commercials.size - 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                }
            ),
            ColumnDef<CommercialItem>(
                id = "index",
                header = StringKey.DETAIL_COL_NUMBER.localized(),
                width = 60.dp * gridScale,
                alignment = TextAlign.Center,
                headerAlignment = TextAlign.Center,
                extractor = { "" },  // Will use row index
                sortable = false,
                cellContent = { item, _, _ ->
                    val index = commercials.indexOf(item) + 1
                    AppText(index.toString(), AppTextStyle.TABLE_CELL_STRONG)
                }
            ),
            ColumnDef(
                id = "clientCode",
                header = StringKey.DETAIL_COL_CUSTOMER_CODE.localized(),
                width = 90.dp * gridScale,
                alignment = TextAlign.Start,
                extractor = { it.clientCode }
            ),
            ColumnDef(
                id = "clientName",
                header = StringKey.DETAIL_COL_CUSTOMER.localized(),
                width = 220.dp * gridScale,
                alignment = TextAlign.Start,
                extractor = { it.clientName }
            ),
            ColumnDef(
                id = "message",
                header = StringKey.DETAIL_COL_MESSAGE.localized(),
                width = 280.dp * gridScale,
                alignment = TextAlign.Start,
                extractor = { it.message }
            ),
            ColumnDef(
                id = "duration",
                header = "sec",
                width = 60.dp * gridScale,
                alignment = TextAlign.Center,
                headerAlignment = TextAlign.Center,
                extractor = { it.durationSeconds.toString() }
            ),
            ColumnDef(
                id = "type",
                header = StringKey.DETAIL_COL_TYPE.localized(),
                width = 200.dp * gridScale,
                alignment = TextAlign.Start,
                // The contract line's SALES item ('Διαφ. TV Κρήτη Σ73.002',
                // 'Διαφημίσεις τηλεόρασης Δ Ω Ρ Α' - the item name carries the
                // gift marker), exactly like the legacy Break Console.
                //
                // When the ERP never covered the document (1.4% of airings) we
                // show NOTHING but the gift marker. Falling back to the spot's
                // programme here - which this column used to do - is the same
                // category error that made the whole column wrong: a programme
                // (ΚΛΕΨΑ) is not a product. An empty cell says "unknown item";
                // a programme name says "this show was the item", which is a lie.
                extractor = {
                    it.salesItem
                        ?: if (it.isGift) StringKey.DETAIL_GIFT_SUFFIX.localized() else ""
                }
            ),
            ColumnDef(
                id = "contract",
                header = StringKey.DETAIL_COL_CONTRACT.localized(),
                width = 80.dp * gridScale,
                alignment = TextAlign.Center,
                headerAlignment = TextAlign.Center,
                // Always the contract NUMBER, like the legacy console - the
                // gift marker lives in the Τύπος column, not here.
                extractor = { it.contract }
            ),
            ColumnDef(
                id = "flow",
                header = StringKey.DETAIL_COL_FLOW.localized(),
                width = 60.dp * gridScale,
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
            dayName = dayNames[date.dayOfWeek] ?: "",
            dayNumber = date.day,
            monthName = monthOfNames[date.month.ordinal],
            year = date.year,
            breakTime = state.breakLabel,
            showName = state.programName,
            totalSpots = state.totalSpots,
            flowSpots = state.flowSpots,
            exceptSpots = state.excludedSpots,
            totalDuration = state.totalDuration,
            flowDuration = state.flowDuration,
            exceptDuration = state.excludedDuration,
            onBack = { onNavIntent(CommercialDetailScreenNavIntent.OnBack) },
            // The VM decides WHICH break is the neighbour; a null one disables
            // the button, exactly as the nullable callbacks used to.
            onPrevious = state.previousBreak?.let { target ->
                { onNavIntent(CommercialDetailScreenNavIntent.OnGoToBreak(target)) }
            },
            onNext = state.nextBreak?.let { target ->
                { onNavIntent(CommercialDetailScreenNavIntent.OnGoToBreak(target)) }
            },
            reportBusy = state.reportBusy,
            reportsAvailable = state.reportsAvailable,
            onIntent = onIntent,
        )

        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant)

        // Data grid with commercials
        EnhancedDataGrid(
            summaryLabel = Strings[StringKey.TIMETABLE_TOTAL_SHORT],
            items = commercials,
            columns = columns,
            modifier = Modifier
                .fillMaxSize()
                .padding(UIConst.paddingSmall),
            state = gridState,
            selectionMode = SelectionMode.SINGLE,
            showRowNumbers = false,
            scale = gridScale,
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
                onIntent(CommercialDetailIntent.MoveRow(fromIndex, toIndex))
            },
            rowKey = { it.id },
            contextMenuItems = { item, rowIndex ->
                listOf(
                    // Print this break's program flow
                    ContextMenuEntry.Item(
                        label = StringKey.TIMETABLE_MENU_PRINT_BREAK.localized(),
                        icon = { AppIcon(AppIcons.print, size = AppIconSize.SMALL) },
                        enabled = commercials.isNotEmpty()
                    ) {
                        onIntent(CommercialDetailIntent.PrintBreak)
                    },

                    // === SEPARATOR ===
                    ContextMenuEntry.Separator,

                    // Edit action
                    ContextMenuEntry.Item(
                        label = StringKey.DETAIL_MENU_EDIT_COMMERCIAL.localized(),
                        icon = { AppIcon(AppIcons.edit, size = AppIconSize.SMALL) },
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
                        icon = { AppIcon(AppIcons.contentCopy, size = AppIconSize.SMALL) },
                        enabled = canEdit,
                        items = listOf(
                            ContextMenuEntry.Item(
                                label = StringKey.COMMON_COPY.localized(),
                                icon = { AppIcon(AppIcons.contentCopy, size = AppIconSize.SMALL) },
                                shortcut = "⌘C"
                            ) {
                                println("Copy: ${item.clientName}")
                            },
                            ContextMenuEntry.Item(
                                label = StringKey.COMMON_CUT.localized(),
                                icon = { AppIcon(AppIcons.contentCut, size = AppIconSize.SMALL) },
                                shortcut = "⌘X"
                            ) {
                                println("Cut: ${item.clientName}")
                            },
                            ContextMenuEntry.Item(
                                label = StringKey.COMMON_PASTE.localized(),
                                icon = { AppIcon(AppIcons.contentPaste, size = AppIconSize.SMALL) },
                                shortcut = "⌘V"
                            ) {
                                println("Paste at index $rowIndex")
                            }
                        )
                    ),

                    // Move submenu
                    ContextMenuEntry.SubMenu(
                        label = StringKey.DETAIL_MENU_MOVE.localized(),
                        icon = { AppIcon(AppIcons.keyboardArrowUp, size = AppIconSize.SMALL) },
                        enabled = canEdit,
                        items = listOf(
                            // The guards below only DISABLE the entries; the
                            // ViewModel re-validates every MoveRow anyway.
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_MOVE_UP.localized(),
                                icon = { AppIcon(AppIcons.keyboardArrowUp, size = AppIconSize.SMALL) },
                                enabled = rowIndex > 0
                            ) {
                                onIntent(CommercialDetailIntent.MoveRow(rowIndex, rowIndex - 1))
                            },
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_MOVE_DOWN.localized(),
                                icon = { AppIcon(AppIcons.keyboardArrowDown, size = AppIconSize.SMALL) },
                                enabled = rowIndex < commercials.size - 1
                            ) {
                                onIntent(CommercialDetailIntent.MoveRow(rowIndex, rowIndex + 1))
                            },
                            ContextMenuEntry.Separator,
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_MOVE_TOP.localized(),
                                enabled = rowIndex > 0
                            ) {
                                onIntent(CommercialDetailIntent.MoveRow(rowIndex, 0))
                            },
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_MOVE_BOTTOM.localized(),
                                enabled = rowIndex < commercials.size - 1
                            ) {
                                onIntent(CommercialDetailIntent.MoveRow(rowIndex, commercials.size - 1))
                            }
                        )
                    ),

                    // === SEPARATOR ===
                    ContextMenuEntry.Separator,

                    // Delete action
                    ContextMenuEntry.Item(
                        label = StringKey.COMMON_DELETE.localized(),
                        icon = { AppIcon(AppIcons.delete, size = AppIconSize.SMALL) },
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
                        icon = { AppIcon(AppIcons.moreVert, size = AppIconSize.SMALL) },
                        items = listOf(
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_PREVIEW.localized(),
                                icon = { AppIcon(AppIcons.playArrow, size = AppIconSize.SMALL) }
                            ) {
                                println("Preview: ${item.message}")
                            },
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_HISTORY.localized(),
                                icon = { AppIcon(AppIcons.history, size = AppIconSize.SMALL) }
                            ) {
                                println("History for: ${item.clientName}")
                            },
                            ContextMenuEntry.Separator,
                            ContextMenuEntry.Item(
                                label = StringKey.DETAIL_MENU_DETAILS.localized(),
                                icon = { AppIcon(AppIcons.info, size = AppIconSize.SMALL) }
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
    /** This break's own report actions, rendered as a ReportToolbar. */
    reportBusy: Boolean,
    reportsAvailable: Boolean,
    onIntent: (CommercialDetailIntent) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(UIConst.paddingRegular)
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
                        AppIconButton(
                            label = Strings[StringKey.COMMON_BACK],
                            icon = AppIcons.arrowBack,
                            onClick = onBack,
                        )

                        Column {
                            AppText(
                                "$dayName - $dayNumber $monthName $year",
                                AppTextStyle.ITEM_TITLE,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)
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

                    Spacer(modifier = Modifier.height(UIConst.paddingSmall))

                    // Stats: a real 2D grid - ΟΛΑ/ΡΟΗΣ/ΕΞΑΙΡ. head the COLUMNS and
                    // each metric is a ROW, so every value sits under its own label.
                    // (The old layout put the three legends in a fourth column with an
                    // empty label, which floated them up onto the header line.)
                    StatGrid(
                        columnLabels = listOf(
                            Strings[StringKey.DETAIL_TOTAL_ALL],
                            Strings[StringKey.DETAIL_TOTAL_FLOW],
                            Strings[StringKey.DETAIL_TOTAL_EXCLUDED],
                        ),
                        rows = listOf(
                            Strings[StringKey.DETAIL_TOTAL_SPOTS] to listOf(
                                totalSpots.toString(),
                                flowSpots.toString(),
                                exceptSpots.toString(),
                            ),
                            Strings[StringKey.DETAIL_TOTAL_DURATION] to listOf(
                                formatDuration(totalDuration),
                                formatDuration(flowDuration),
                                formatDuration(exceptDuration),
                            ),
                        ),
                    )

                    Spacer(modifier = Modifier.height(UIConst.paddingCompact))

                    // This break's own report (preview / print / export PDF) - the
                    // same toolkit the month report uses, wearing the platform's
                    // control geometry.
                    ReportToolbar(
                        onPreview = { onIntent(CommercialDetailIntent.PreviewBreak) },
                        onPrint = { onIntent(CommercialDetailIntent.PrintBreak) },
                        onExportPdf = { onIntent(CommercialDetailIntent.ExportBreakPdf) },
                        busy = reportBusy,
                        available = reportsAvailable,
                        labels = reportToolbarLabels(),
                        metrics = reportToolbarMetrics(),
                    )
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

                        Spacer(modifier = Modifier.height(UIConst.paddingRegular))
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)
                    ) {
                        // Print moved into the ReportToolbar on the left (it is one
                        // of three report actions, not a lone navigation button).
                        AppButton(
                            text = Strings[StringKey.DETAIL_PREVIOUS],
                            onClick = { onPrevious?.invoke() },
                            variant = AppButtonVariant.SECONDARY,
                            enabled = onPrevious != null,
                            leadingIcon = AppIcons.arrowBack,
                        )

                        AppButton(
                            text = Strings[StringKey.DETAIL_NEXT],
                            onClick = { onNext?.invoke() },
                            variant = AppButtonVariant.SECONDARY,
                            enabled = onNext != null,
                            trailingIcon = AppIcons.arrowForward,
                        )
                    }
                }
            }
        }
    }
}

/** Stat-column width: domain geometry (aligned columns of counts), not spacing. */
private val statCellWidth = 64.dp

/**
 * The header stats as a proper table: [columnLabels] head the columns
 * (ΟΛΑ / ΡΟΗΣ / ΕΞΑΙΡ.) and each row is one metric, so a value is always
 * directly under the label that names it.
 */
@Composable
private fun StatGrid(
    columnLabels: List<String>,
    rows: List<Pair<String, List<String>>>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
        // Row-label column: a blank cell keeps it aligned with the header line
        // (an empty AppText, so it scales with the font-size preference).
        Column(horizontalAlignment = Alignment.Start) {
            AppText("", AppTextStyle.STAT_LABEL)
            rows.forEach { (rowLabel, _) ->
                AppText(rowLabel, AppTextStyle.STAT_LABEL)
            }
        }
        columnLabels.forEachIndexed { column, columnLabel ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(statCellWidth),
            ) {
                AppText(columnLabel, AppTextStyle.STAT_LABEL, textAlign = TextAlign.Center)
                rows.forEach { (_, values) ->
                    AppText(
                        values.getOrElse(column) { "" },
                        AppTextStyle.TABLE_CELL_STRONG,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// ═══ Previews ══════════════════════════════════════════════════════════
//
// The console has two shapes, and only one of them is the happy path: a break
// with airings (grid rows, real stats, the paging chain) and a break that has
// been emptied (a grid with no rows, zeroed stats, no neighbour to page to).

/** The 20:30 break of a Friday on Crete TV - paid spots plus two flow items. */
private val previewCommercials = persistentListOf(
    CommercialItem(
        id = 1,
        clientCode = "CUS-1042",
        clientName = "Minoan Lines",
        message = "Summer crossings 2026",
        durationSeconds = 30,
        type = "SPOT",
        salesItem = "TV Advertising Crete S73.002",
        contract = "S73.002",
        flow = "",
    ),
    CommercialItem(
        id = 2,
        clientCode = "CUS-2210",
        clientName = "Chania Olive Coop",
        message = "Extra virgin - harvest campaign",
        durationSeconds = 20,
        type = "SPOT",
        salesItem = "TV Advertising Crete S74.118",
        contract = "S74.118",
        flow = "",
    ),
    CommercialItem(
        id = 3,
        clientCode = "CUS-0917",
        clientName = "Heraklion Motors",
        message = "New showroom opening",
        durationSeconds = 45,
        type = "SPOT",
        salesItem = "TV Advertising Crete S75.006",
        contract = "S75.006",
        flow = "",
    ),
    CommercialItem(
        id = 4,
        clientCode = "CUS-3381",
        clientName = "Crete TV",
        message = "Coming up after the break",
        durationSeconds = 15,
        type = "TRAILER",
        contract = "S75.900",
        isGift = true,
        flow = FLOW_ROH,
    ),
    CommercialItem(
        id = 5,
        clientCode = "CUS-1503",
        clientName = "Aegean Bank",
        message = "Home loans - summer rates",
        durationSeconds = 30,
        type = "SPOT",
        salesItem = "TV Advertising Crete S73.441",
        contract = "S73.441",
        flow = "",
        excludeFromReports = true,
    ),
    CommercialItem(
        id = 6,
        clientCode = "CUS-3381",
        clientName = "Crete TV",
        message = "Evening News sponsor billboard",
        durationSeconds = 25,
        type = "BILLBOARD",
        contract = "S75.900",
        flow = FLOW_ROH,
    ),
)

/** A break with several commercials: the grid, the stats, both paging arrows. */
@Preview
@Composable
private fun CommercialDetailScreenPreview() = AppPreview(padded = false) {
    CommercialDetailScreen(
        state = CommercialDetailState(
            date = LocalDate(2026, 7, 3),
            breakLabel = "20:30",
            commercials = previewCommercials,
            programName = "Evening News",
            previousBreak = BreakRef(LocalTime(19, 45), "19:45", spotCount = 4),
            nextBreak = BreakRef(LocalTime(21, 45), "21:45", spotCount = 5),
            canEdit = true,
            totalSpots = 6,
            flowSpots = 2,
            excludedSpots = 4,
            totalDuration = 165,
            flowDuration = 40,
            excludedDuration = 125,
            reportsAvailable = true,
        ),
        onIntent = {},
        onNavIntent = {},
    )
}

/**
 * An empty break: no rows, zeroed stats, no programme on record - and only a
 * previous neighbour, so the Next button is disabled (the day's last occupied break).
 */
@Preview
@Composable
private fun CommercialDetailScreenEmptyPreview() = AppPreview(padded = false) {
    CommercialDetailScreen(
        state = CommercialDetailState(
            date = LocalDate(2026, 7, 3),
            breakLabel = "23:00",
            commercials = persistentListOf(),
            previousBreak = BreakRef(LocalTime(21, 45), "21:45", spotCount = 5),
            canEdit = true,
            reportsAvailable = true,
        ),
        onIntent = {},
        onNavIntent = {},
    )
}
