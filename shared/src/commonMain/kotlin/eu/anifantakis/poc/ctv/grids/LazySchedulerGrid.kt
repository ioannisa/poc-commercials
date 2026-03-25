package eu.anifantakis.poc.ctv.grids

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
import androidx.compose.runtime.key
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
    contextMenuItems: ((BreakSlot, LocalDate, SchedulerCellData?) -> List<ContextMenuEntry>)? = null
) {
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
    val visibleBreaks = remember(breaks, cellData, year, month) {
        breaks.filter { breakSlot ->
            cellData.keys.any { key ->
                val (breakId, date) = key
                breakId == breakSlot.id &&
                        date.year == year &&
                        date.month.ordinal + 1 == month &&
                        (cellData[key]?.spotCount ?: 0) > 0
            }
        }.ifEmpty {
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
                color = if (hasFocus) MaterialTheme.colorScheme.primary else Color.Gray
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
                .background(Color(0xFFE8E8E8))
        ) {
            // Break time header (frozen)
            Box(
                modifier = Modifier
                    .width(breakColumnWidth)
                    .fillMaxHeight()
                    .background(Color(0xFFE8E8E8))
                    .border(0.5.dp, Color.Gray),
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
                            isSelected = colIndex == selectedColumn
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
                    Box(
                        modifier = Modifier
                            .width(breakColumnWidth)
                            .fillMaxHeight()
                            .background(
                                if (isRowSelected) Color(0xFFE53935) else Color(0xFFF5F5F5)
                            )
                            .border(0.5.dp, Color.LightGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = formatTime(breakSlot.time.hour, breakSlot.time.minute),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isRowSelected) Color.White else Color.Black
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
                                key(breakSlot.id, colIndex) {
                                    val cellKey = SchedulerKey(breakSlot.id, date)
                                    val data = cellData[cellKey]
                                    val isModified = modifiedCells.contains(cellKey)

                                    // Pass the menu items provider lambda to the cell
                                    // The cell will call this only when clicked
                                    // PERFORMANCE: Wrap LocalDate in StableDate to satisfy Compose stability
                                    GridCell(
                                        data = data,
                                        dateWrapper = StableDate(date),
                                        width = dayColumnWidth,
                                        isModified = isModified,
                                        rowIndex = rowIndex,
                                        colIndex = colIndex,
                                        selectedRow = selectedRow,
                                        selectedColumn = selectedColumn,
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
        }

        // ===== FOOTER (TOTALS) =====
        if (dailyTotals != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .background(Color(0xFFE8E8E8))
            ) {
                // Totals label
                Box(
                    modifier = Modifier
                        .width(breakColumnWidth)
                        .fillMaxHeight()
                        .border(0.5.dp, Color.Gray),
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
                                    .border(0.5.dp, Color.Gray),
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
    isSelected: Boolean = false
) {
    val isWeekend = date.value.dayOfWeek == DayOfWeek.SATURDAY || date.value.dayOfWeek == DayOfWeek.SUNDAY
    val bgColor = when {
        isSelected -> Color(0xFFE53935)
        isWeekend -> Color(0xFFFFE0B2)
        else -> Color(0xFFE8E8E8)
    }

    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(bgColor)
            .border(0.5.dp, Color.Gray),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = date.value.dayOfWeek.toGreekAbbrLazy(),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) Color.White else Color.Black
        )
        Text(
            text = date.value.day.toString(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) Color.White else Color.Black
        )
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
    rowIndex: Int,
    colIndex: Int,
    selectedRow: Int,
    selectedColumn: Int,
    onSelect: () -> Unit,
    onDoubleClick: () -> Unit,
    menuEntriesProvider: (() -> List<ContextMenuEntry>)? = null
) {
    // Extract LocalDate from stable wrapper (StableDate is @Immutable)
    val date = dateWrapper.value

    val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    val spotCount = data?.spotCount ?: 0
    val isSelected = rowIndex == selectedRow && colIndex == selectedColumn

    // Local menu state for right-click handling
    var menuVisible by remember { mutableStateOf(false) }
    var clickOffset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    // Modified cells show black background
    val bgColor = remember(isModified, data?.zoneColor, spotCount, isWeekend) {
        when {
            isModified -> Color.Black
            data?.zoneColor != null && data.zoneColor != Color.White -> data.zoneColor.copy(alpha = 0.4f)
            spotCount > 10 -> Color(0xFFFF69B4).copy(alpha = 0.4f)
            spotCount > 5 -> Color(0xFF90EE90).copy(alpha = 0.4f)
            spotCount > 0 -> Color(0xFF87CEEB).copy(alpha = 0.4f)
            isWeekend -> Color(0xFFFFE0B2).copy(alpha = 0.3f)
            else -> Color.White
        }
    }

    val textColor = if (isModified) Color.White else Color.Black
    val selectionColor = Color(0xFFE53935)
    val normalBorderColor = Color.LightGray

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
                onClick = onSelect,
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