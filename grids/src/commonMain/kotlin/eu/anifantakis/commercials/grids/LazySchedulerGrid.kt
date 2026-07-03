package eu.anifantakis.commercials.grids

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * Lazy Scheduler Grid - Uses LazyColumn for virtualization (better performance)
 * Only renders visible rows, significantly reducing the number of composables.
 * * Update: Now handles Right-Click Context Menus locally within cells for correct positioning.
 */
@Composable
fun LazySchedulerGrid(
    breaks: ImmutableList<BreakSlot>,
    cellData: ImmutableMap<SchedulerKey, SchedulerCellData>,
    modifiedCells: ImmutableSet<SchedulerKey> = persistentSetOf(),
    year: Int,
    month: Int,
    modifier: Modifier = Modifier,
    breakColumnWidth: Dp = 70.dp,
    dayColumnWidth: Dp = 42.dp,
    rowHeight: Dp = 28.dp,
    headerHeight: Dp = 50.dp,
    initialSelectedRow: Int = 0,
    initialSelectedColumn: Int = 0,
    onSelectionChange: ((row: Int, col: Int) -> Unit)? = null,
    onCellClick: ((BreakSlot, LocalDate, SchedulerCellData?) -> Unit)? = null,
    onCellDoubleClick: ((BreakSlot, LocalDate, SchedulerCellData?) -> Unit)? = null,
    onAddSpot: ((BreakSlot, LocalDate) -> Unit)? = null,
    onDeleteSpot: ((BreakSlot, LocalDate) -> Unit)? = null,
    dailyTotals: ImmutableMap<StableDate, DailyStats>? = null,
    contextMenuItems: ((BreakSlot, LocalDate, SchedulerCellData?) -> List<ContextMenuEntry>)? = null,
    // Popup menus for the frozen edges: the day-header row (top) and the
    // break-time column (left)
    dayHeaderContextMenuItems: ((LocalDate) -> List<ContextMenuEntry>)? = null,
    breakHeaderContextMenuItems: ((BreakSlot) -> List<ContextMenuEntry>)? = null
) {
    val palette = gridPalette()

    // Keyboard navigation state - use rememberSaveable to survive configuration changes
    var selectedRow by rememberSaveable { mutableStateOf(initialSelectedRow) }
    var selectedColumn by rememberSaveable { mutableStateOf(initialSelectedColumn) }
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    // Sync with external selection state
    LaunchedEffect(initialSelectedRow, initialSelectedColumn) {
        selectedRow = initialSelectedRow
        selectedColumn = initialSelectedColumn
    }

    // Generate days for this month
    val daysInMonth = remember(year, month) {
        when {
            month == 2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            month in listOf(4, 6, 9, 11) -> 30
            else -> 31
        }
    }

    val allDays = remember(year, month, daysInMonth) {
        (1..daysInMonth).map { day -> LocalDate(year, month, day) }
    }

    // Filter breaks to only show those with data (condensed mode)
    // Single O(cells) pass to collect break ids with spots, then O(breaks) filter
    val visibleBreaks = remember(breaks, cellData, year, month) {
        val breakIdsWithSpots = buildSet {
            for ((key, data) in cellData) {
                if (data.spotCount > 0 &&
                    key.date.year == year &&
                    key.date.month.ordinal + 1 == month
                ) {
                    add(key.breakId)
                }
            }
        }
        breaks.filter { it.id in breakIdsWithSpots }.ifEmpty {
            breaks.take(20)
        }
    }

    // Scroll states - use rememberSaveable to survive configuration changes
    // Optimization: Use cached state with prefetch buffer (500.dp) to reduce jank
    val lazyListState = rememberCachedLazyListState(cacheConfig = CacheWindowConfig.Fixed(300.dp))
    val horizontalScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

    // Calculate total content width
    val totalDaysWidth = dayColumnWidth * daysInMonth

    // Helper to update selection and notify parent
    fun updateSelection(newRow: Int, newCol: Int) {
        selectedRow = newRow
        selectedColumn = newCol
        onSelectionChange?.invoke(newRow, newCol)
    }

    // Keyboard handler
    fun handleKeyboard(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false

        val maxRow = visibleBreaks.size - 1
        val maxCol = daysInMonth - 1

        return when (event.key) {
            Key.DirectionUp -> {
                val newRow = (selectedRow - 1).coerceIn(0, maxRow.coerceAtLeast(0))
                updateSelection(newRow, selectedColumn)
                true
            }
            Key.DirectionDown -> {
                val newRow = (selectedRow + 1).coerceIn(0, maxRow.coerceAtLeast(0))
                updateSelection(newRow, selectedColumn)
                true
            }
            Key.DirectionLeft -> {
                val newCol = (selectedColumn - 1).coerceIn(0, maxCol)
                updateSelection(selectedRow, newCol)
                true
            }
            Key.DirectionRight -> {
                val newCol = (selectedColumn + 1).coerceIn(0, maxCol)
                updateSelection(selectedRow, newCol)
                true
            }
            Key.Enter -> {
                val breakSlot = visibleBreaks.getOrNull(selectedRow)
                val date = allDays.getOrNull(selectedColumn)
                if (breakSlot != null && date != null) {
                    val data = cellData[SchedulerKey(breakSlot.id, date)]
                    onCellDoubleClick?.invoke(breakSlot, date, data)
                }
                true
            }
            Key.A -> {
                val breakSlot = visibleBreaks.getOrNull(selectedRow)
                val date = allDays.getOrNull(selectedColumn)
                if (breakSlot != null && date != null) {
                    onAddSpot?.invoke(breakSlot, date)
                }
                true
            }
            Key.D, Key.Delete, Key.Backspace -> {
                val breakSlot = visibleBreaks.getOrNull(selectedRow)
                val date = allDays.getOrNull(selectedColumn)
                if (breakSlot != null && date != null) {
                    onDeleteSpot?.invoke(breakSlot, date)
                }
                true
            }
            Key.PageUp -> {
                val pageSize = lazyListState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                updateSelection((selectedRow - pageSize).coerceAtLeast(0), selectedColumn)
                true
            }
            Key.PageDown -> {
                val pageSize = lazyListState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                updateSelection((selectedRow + pageSize).coerceIn(0, maxRow.coerceAtLeast(0)), selectedColumn)
                true
            }
            Key.MoveHome -> {
                // Home: first column; Ctrl+Home: top-left corner
                if (event.isCtrlPressed) updateSelection(0, 0)
                else updateSelection(selectedRow, 0)
                true
            }
            Key.MoveEnd -> {
                // End: last column; Ctrl+End: bottom-right corner
                if (event.isCtrlPressed) updateSelection(maxRow.coerceAtLeast(0), maxCol)
                else updateSelection(selectedRow, maxCol)
                true
            }
            else -> false
        }
    }

    // Auto-scroll logic remains the same...
    val dayColumnWidthPx = with(density) { dayColumnWidth.toPx() }

    // Vertical scroll - use LazyListState
    LaunchedEffect(selectedRow) {
        if (selectedRow >= 0) {
            val firstVisible = lazyListState.firstVisibleItemIndex
            val lastVisible = firstVisible + lazyListState.layoutInfo.visibleItemsInfo.size - 1

            when {
                selectedRow < firstVisible -> lazyListState.animateScrollToItem(selectedRow)
                selectedRow > lastVisible -> lazyListState.animateScrollToItem(selectedRow - lazyListState.layoutInfo.visibleItemsInfo.size + 1)
            }
        }
    }

    // Horizontal scroll - auto-scroll to keep selection visible
    LaunchedEffect(selectedColumn, dayColumnWidthPx) {
        if (selectedColumn >= 0) {
            val targetLeft = (selectedColumn * dayColumnWidthPx).toInt()
            val targetRight = targetLeft + dayColumnWidthPx.toInt()
            val visibleLeft = horizontalScrollState.value
            val visibleRight = visibleLeft + horizontalScrollState.viewportSize

            when {
                targetLeft < visibleLeft -> horizontalScrollState.animateScrollTo(targetLeft)
                targetRight > visibleRight -> horizontalScrollState.animateScrollTo(targetRight - horizontalScrollState.viewportSize)
            }
        }
    }

    Column(
        modifier = modifier
            .border(
                width = 2.dp,
                color = if (hasFocus) MaterialTheme.colorScheme.primary else palette.gridBorderUnfocused
            )
            .focusRequester(focusRequester)
            .focusable()
            .onFocusChanged { hasFocus = it.hasFocus }
            .onPreviewKeyEvent { handleKeyboard(it) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                focusRequester.requestFocus()
            }
    ) {
        // ===== HEADER ROW =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .background(palette.headerBackground)
        ) {
            // Break time header (frozen)
            Box(
                modifier = Modifier
                    .width(breakColumnWidth)
                    .fillMaxHeight()
                    .background(palette.headerBackground)
                    .border(0.5.dp, palette.headerBorder),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Ώρα/Μέρα",
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }

            // Day headers (scrollable)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(horizontalScrollState)
            ) {
                Row(modifier = Modifier.width(totalDaysWidth)) {
                    allDays.forEachIndexed { colIndex, date ->
                        LazyDayHeader(
                            date = StableDate(date),
                            width = dayColumnWidth,
                            isSelected = colIndex == selectedColumn,
                            menuEntriesProvider = if (dayHeaderContextMenuItems != null) {
                                { dayHeaderContextMenuItems(date) }
                            } else null,
                            onMenuOpen = { updateSelection(selectedRow, colIndex) }
                        )
                    }
                }
            }
        }

        // ===== BODY - Single LazyColumn for proper vertical scroll sync =====
        CachedLazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            itemsIndexed(
                items = visibleBreaks,
                key = { _, breakSlot -> "row_${breakSlot.id}" }
            ) { rowIndex, breakSlot ->
                val isRowSelected = rowIndex == selectedRow

                Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
                    // Break time column (frozen - not in horizontal scroll)
                    FrozenHeaderBox(
                        modifier = Modifier
                            .width(breakColumnWidth)
                            .fillMaxHeight()
                            .background(
                                if (isRowSelected) palette.selectedRowHeader else palette.frozenRowHeader
                            )
                            .border(0.5.dp, palette.cellBorder),
                        menuEntriesProvider = if (breakHeaderContextMenuItems != null) {
                            { breakHeaderContextMenuItems(breakSlot) }
                        } else null,
                        onMenuOpen = { updateSelection(rowIndex, selectedColumn) }
                    ) {
                        Text(
                            text = formatTime(breakSlot.time.hour, breakSlot.time.minute),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isRowSelected) palette.onSelectionHeader else palette.cellText
                        )
                    }

                    // Cells (scrollable horizontally)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(horizontalScrollState)
                    ) {
                        Row(modifier = Modifier.width(totalDaysWidth)) {
                            allDays.forEachIndexed { colIndex, date ->
                                val cellKey = SchedulerKey(breakSlot.id, date)
                                val data = cellData[cellKey]
                                val isModified = modifiedCells.contains(cellKey)

                                // Pass the menu items provider lambda to the cell
                                // The cell will call this only when clicked
                                // PERFORMANCE: Wrap LocalDate in StableDate to satisfy Compose stability.
                                // isSelected is resolved HERE so that a selection move only
                                // recomposes the two affected cells - all others skip.
                                GridCell(
                                    data = data,
                                    dateWrapper = StableDate(date),
                                    width = dayColumnWidth,
                                    isModified = isModified,
                                    isSelected = rowIndex == selectedRow && colIndex == selectedColumn,
                                    onSelect = { updateSelection(rowIndex, colIndex) },
                                    onDoubleClick = { onCellDoubleClick?.invoke(breakSlot, date, data) },
                                    // Pass the menu provider if the parent provided contextMenuItems
                                    menuEntriesProvider = if (contextMenuItems != null) {
                                        { contextMenuItems(breakSlot, date, data) }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }
        }

        // ===== FOOTER (TOTALS) =====
        if (dailyTotals != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .background(palette.headerBackground)
            ) {
                // Totals label
                Box(
                    modifier = Modifier
                        .width(breakColumnWidth)
                        .fillMaxHeight()
                        .border(0.5.dp, palette.headerBorder),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Σύνολα",
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }

                // Daily totals (scrollable)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(horizontalScrollState)
                ) {
                    Row(modifier = Modifier.width(totalDaysWidth)) {
                        allDays.forEach { date ->
                            val total = dailyTotals[StableDate(date)]
                            Box(
                                modifier = Modifier
                                    .width(dayColumnWidth)
                                    .fillMaxHeight()
                                    .border(0.5.dp, palette.headerBorder),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = (total?.spotCount ?: 0).toString(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LazyDayHeader(
    date: StableDate,
    width: Dp,
    isSelected: Boolean = false,
    menuEntriesProvider: (() -> List<ContextMenuEntry>)? = null,
    onMenuOpen: (() -> Unit)? = null
) {
    val palette = gridPalette()
    val isWeekend = date.value.dayOfWeek == DayOfWeek.SATURDAY || date.value.dayOfWeek == DayOfWeek.SUNDAY
    val bgColor = when {
        isSelected -> palette.selectedColumnHeader
        isWeekend -> palette.weekendColumn
        else -> palette.headerBackground
    }

    FrozenHeaderBox(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(bgColor)
            .border(0.5.dp, palette.headerBorder),
        menuEntriesProvider = menuEntriesProvider,
        onMenuOpen = onMenuOpen
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = date.value.dayOfWeek.toGreekAbbrLazy(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) palette.onSelectionHeader else palette.cellText
            )
            Text(
                text = date.value.day.toString(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) palette.onSelectionHeader else palette.cellText
            )
        }
    }
}

/**
 * A frozen header cell (day column header or break-time row header) that can
 * host a right-click context menu. The menu is rendered locally inside the
 * box so its offset is relative to the header cell (same pattern as GridCell).
 * [onMenuOpen] fires when the menu opens - used to move the grid selection to
 * the right-clicked column/row, mirroring how cells select on right-click.
 */
@Composable
private fun FrozenHeaderBox(
    modifier: Modifier,
    menuEntriesProvider: (() -> List<ContextMenuEntry>)?,
    onMenuOpen: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var menuVisible by remember { mutableStateOf(false) }
    var clickOffset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    Box(
        modifier = modifier.let { mod ->
            if (menuEntriesProvider != null) {
                mod.onRightClick { offset ->
                    clickOffset = offset
                    menuVisible = true
                    onMenuOpen?.invoke()
                }
            } else mod
        },
        contentAlignment = Alignment.Center
    ) {
        content()

        if (menuVisible && menuEntriesProvider != null) {
            val entries = menuEntriesProvider()
            if (entries.isNotEmpty()) {
                val dpOffset = with(density) {
                    DpOffset(clickOffset.x.toDp(), clickOffset.y.toDp())
                }

                RichContextMenu(
                    expanded = true,
                    onDismissRequest = { menuVisible = false },
                    entries = entries.toImmutableList(),
                    offset = dpOffset
                )
            }
        }
    }
}

private fun DayOfWeek.toGreekAbbrLazy(): String = when (this) {
    DayOfWeek.MONDAY -> "ΔΕ"
    DayOfWeek.TUESDAY -> "ΤΡ"
    DayOfWeek.WEDNESDAY -> "ΤΕ"
    DayOfWeek.THURSDAY -> "ΠΕ"
    DayOfWeek.FRIDAY -> "ΠΑ"
    DayOfWeek.SATURDAY -> "ΣΑ"
    DayOfWeek.SUNDAY -> "ΚΥ"
}

@Composable
private fun GridCell(
    data: SchedulerCellData?,
    dateWrapper: StableDate,
    width: Dp,
    isModified: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDoubleClick: () -> Unit,
    menuEntriesProvider: (() -> List<ContextMenuEntry>)? = null
) {
    // Extract LocalDate from stable wrapper (StableDate is @Immutable)
    val date = dateWrapper.value

    val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    val spotCount = data?.spotCount ?: 0

    // Local menu state for right-click handling
    var menuVisible by remember { mutableStateOf(false) }
    var clickOffset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    val palette = gridPalette()

    // Modified cells show the palette's high-contrast marker (black chip on
    // light, inverted light chip on dark). Zone colours arrive from the
    // server as LIGHT-theme values; Color.White is the "no zone" sentinel
    // (compared RAW, before tinting) and zoneTint adapts the rest per theme.
    val bgColor = when {
        isModified -> palette.modifiedCellBackground
        data?.zoneColor != null && data.zoneColor != Color.White -> palette.zoneTint(data.zoneColor).copy(alpha = 0.4f)
        spotCount > 10 -> palette.densityHigh.copy(alpha = 0.4f)
        spotCount > 5 -> palette.densityMedium.copy(alpha = 0.4f)
        spotCount > 0 -> palette.densityLow.copy(alpha = 0.4f)
        isWeekend -> palette.weekendColumn.copy(alpha = 0.3f)
        else -> palette.cellBackground
    }

    val textColor = if (isModified) palette.onModifiedCell else palette.cellText
    val selectionColor = palette.selectionBorder
    val normalBorderColor = palette.cellBorder

    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .drawBehind {
                // Draw background
                drawRect(bgColor)
                // Draw border - inset to draw inside bounds
                if (isSelected) {
                    val strokeWidth = 3.dp.toPx()
                    val halfStroke = strokeWidth / 2
                    drawRect(
                        color = selectionColor,
                        topLeft = androidx.compose.ui.geometry.Offset(halfStroke, halfStroke),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - strokeWidth,
                            size.height - strokeWidth
                        ),
                        style = Stroke(strokeWidth)
                    )
                } else {
                    val strokeWidth = 0.5.dp.toPx()
                    val halfStroke = strokeWidth / 2
                    drawRect(
                        color = normalBorderColor,
                        topLeft = androidx.compose.ui.geometry.Offset(halfStroke, halfStroke),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - strokeWidth,
                            size.height - strokeWidth
                        ),
                        style = Stroke(strokeWidth)
                    )
                }
            }
            // Use separate onRightClick modifier for reliable right-click detection (same pattern as EnhancedDataGrid)
            .let { mod ->
                if (menuEntriesProvider != null) {
                    mod.onRightClick { offset ->
                        clickOffset = offset
                        menuVisible = true
                        onSelect()
                    }
                } else mod
            }
            .multiButtonClickable(
                onClick = { onSelect() },
                onDoubleClick = onDoubleClick,
                onRightClick = null  // Right-click handled by separate modifier above
            ),
        contentAlignment = Alignment.Center
    ) {
        if (spotCount > 0) {
            Text(
                text = spotCount.toString(),
                fontSize = 10.sp,
                fontWeight = if (isSelected || isModified) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = textColor
            )
        }

        // Render Context Menu LOCALLY inside the cell
        // This ensures the offset is relative to the cell, fixing the "jumping menu" bug.
        if (menuVisible && menuEntriesProvider != null) {
            val entries = menuEntriesProvider()
            if (entries.isNotEmpty()) {
                val dpOffset = with(density) {
                    DpOffset(clickOffset.x.toDp(), clickOffset.y.toDp())
                }

                RichContextMenu(
                    expanded = true,
                    onDismissRequest = { menuVisible = false },
                    entries = entries.toImmutableList(),
                    offset = dpOffset
                )
            }
        }
    }
}