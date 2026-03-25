package eu.anifantakis.poc.ctv.grids

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch

// ============================================================================
// CORE DATA STRUCTURES
// ============================================================================

/**
 * Sort direction for columns
 */
enum class SortDirection {
    ASCENDING,
    DESCENDING,
    NONE
}

/**
 * Selection mode for the grid
 */
enum class SelectionMode {
    NONE,
    SINGLE,
    MULTIPLE
}

/**
 * Cell position in the grid
 */
data class CellPosition(
    val row: Int,
    val column: Int
) {
    companion object {
        val NONE = CellPosition(-1, -1)
    }

    fun isValid() = row >= 0 && column >= 0
}

/**
 * Column definition for DataGrid
 */
data class ColumnDefinition<T>(
    val id: String,
    val header: String,
    val width: Dp = 120.dp,
    val minWidth: Dp = 40.dp,
    val maxWidth: Dp = 500.dp,
    val resizable: Boolean = true,
    val reorderable: Boolean = true,
    val sortable: Boolean = true,
    val visible: Boolean = true,
    val alignment: TextAlign = TextAlign.Start,
    val headerAlignment: TextAlign = TextAlign.Start,
    val extractor: (T) -> String,
    val cellBackground: ((T) -> Color)? = null,
    val cellTextColor: ((T) -> Color)? = null,
    val fontWeight: ((T) -> FontWeight)? = null,
    val comparator: Comparator<T>? = null,  // Custom sort comparator
    val cellContent: (@Composable (T, Boolean) -> Unit)? = null  // Custom cell renderer
)

/**
 * Persistent state for column widths and order
 */
@Stable
data class ColumnState(
    val id: String,
    val width: Dp,
    val orderIndex: Int,
    val visible: Boolean = true
)

/**
 * Complete grid state including columns, selection, sort
 */
@Stable
class DataGridState<T>(
    columns: List<ColumnDefinition<T>>,
    initialSortColumn: String? = null,
    initialSortDirection: SortDirection = SortDirection.NONE
) {
    var columnStates by mutableStateOf(
        columns.mapIndexed { index, col ->
            ColumnState(col.id, col.width, index, col.visible)
        }
    )

    var sortColumn by mutableStateOf(initialSortColumn)
    var sortDirection by mutableStateOf(initialSortDirection)
    var selectedRows by mutableStateOf(setOf<Int>())
    var focusedRow by mutableStateOf(-1)
    var focusedColumn by mutableStateOf(-1)

    fun getColumnWidth(columnId: String): Dp {
        return columnStates.find { it.id == columnId }?.width ?: 120.dp
    }

    fun updateColumnWidth(columnId: String, newWidth: Dp, columns: List<ColumnDefinition<T>>) {
        val colDef = columns.find { it.id == columnId } ?: return
        val clampedWidth = newWidth.coerceIn(colDef.minWidth, colDef.maxWidth)
        columnStates = columnStates.map { state ->
            if (state.id == columnId) state.copy(width = clampedWidth) else state
        }
    }

    fun swapColumns(fromId: String, toId: String) {
        val fromIndex = columnStates.find { it.id == fromId }?.orderIndex ?: return
        val toIndex = columnStates.find { it.id == toId }?.orderIndex ?: return
        columnStates = columnStates.map { state ->
            when (state.id) {
                fromId -> state.copy(orderIndex = toIndex)
                toId -> state.copy(orderIndex = fromIndex)
                else -> state
            }
        }
    }

    fun toggleSort(columnId: String) {
        if (sortColumn == columnId) {
            sortDirection = when (sortDirection) {
                SortDirection.NONE -> SortDirection.ASCENDING
                SortDirection.ASCENDING -> SortDirection.DESCENDING
                SortDirection.DESCENDING -> SortDirection.NONE
            }
            if (sortDirection == SortDirection.NONE) {
                sortColumn = null
            }
        } else {
            sortColumn = columnId
            sortDirection = SortDirection.ASCENDING
        }
    }

    fun selectRow(rowIndex: Int, mode: SelectionMode, isCtrlPressed: Boolean = false, isShiftPressed: Boolean = false) {
        when (mode) {
            SelectionMode.NONE -> { /* Do nothing */ }
            SelectionMode.SINGLE -> {
                selectedRows = setOf(rowIndex)
                focusedRow = rowIndex
            }
            SelectionMode.MULTIPLE -> {
                selectedRows = when {
                    isCtrlPressed -> {
                        if (rowIndex in selectedRows) selectedRows - rowIndex
                        else selectedRows + rowIndex
                    }
                    isShiftPressed && focusedRow >= 0 -> {
                        val range = if (rowIndex > focusedRow) focusedRow..rowIndex else rowIndex..focusedRow
                        selectedRows + range.toSet()
                    }
                    else -> setOf(rowIndex)
                }
                focusedRow = rowIndex
            }
        }
    }

    fun clearSelection() {
        selectedRows = emptySet()
        focusedRow = -1
    }

    fun selectAll(itemCount: Int) {
        selectedRows = (0 until itemCount).toSet()
    }

    fun moveFocus(deltaRow: Int, deltaCol: Int, rowCount: Int, colCount: Int) {
        val newRow = (focusedRow + deltaRow).coerceIn(0, (rowCount - 1).coerceAtLeast(0))
        val newCol = (focusedColumn + deltaCol).coerceIn(0, (colCount - 1).coerceAtLeast(0))
        focusedRow = newRow
        focusedColumn = newCol
    }
}

@Composable
fun <T> rememberDataGridState(
    columns: ImmutableList<ColumnDefinition<T>>,
    initialSortColumn: String? = null,
    initialSortDirection: SortDirection = SortDirection.NONE
): DataGridState<T> {
    return remember(columns) {
        DataGridState(columns, initialSortColumn, initialSortDirection)
    }
}

// ============================================================================
// GRID COLORS & THEME
// ============================================================================

object GridColors {
    val headerBackground = Color(0xFFE8E8E8)
    val headerBorder = Color(0xFFBDBDBD)
    val rowAlternate = Color(0xFFF5F5F5)
    val rowSelected = Color(0xFFBBDEFB)
    val rowHovered = Color(0xFFE3F2FD)
    val rowFocused = Color(0xFF90CAF9)
    val cellBorder = Color(0xFFE0E0E0)
    val resizeHandle = Color(0xFF9E9E9E)
    val negativeValue = Color(0xFFD32F2F)
    val warningValue = Color(0xFFFF9800)
    val positiveValue = Color(0xFF388E3C)

    // Scheduler specific
    val weekendColumn = Color(0xFFFFE0B2)
    val selectedRowHeader = Color(0xFFEF5350)
    val selectedColumnHeader = Color(0xFFEF5350)
    val zonePrime = Color(0xFFFF69B4)
    val zoneStandard = Color(0xFF87CEEB)
    val zoneSpecial = Color(0xFF90EE90)
    val zoneDefault = Color.White
}

// ============================================================================
// RIGHT-CLICK SUPPORT FOR DESKTOP/MULTIPLATFORM
// ============================================================================

/**
 * Result of a pointer click event, containing the click type and position.
 */
data class ClickResult(
    val type: ClickType,
    val position: Offset
)

/**
 * Types of pointer clicks that can be detected.
 */
enum class ClickType {
    PRIMARY,        // Left-click
    SECONDARY,      // Right-click
    PRIMARY_DOUBLE  // Double left-click
}

/**
 * Configuration for click detection behavior.
 */
data class ClickConfig(
    val enablePrimaryClick: Boolean = true,
    val enableSecondaryClick: Boolean = true,
    val enableDoubleClick: Boolean = true,
    val doubleClickTimeoutMs: Long = 400L  // Platform default is typically 400ms
)

/**
 * A Modifier extension that provides comprehensive click handling for Desktop targets,
 * distinguishing between left-click (primary), right-click (secondary), and double-click.
 *
 * This replaces `combinedClickable` when you need to detect right-clicks for context menus.
 *
 * Usage:
 * ```kotlin
 * Modifier.multiButtonClickable(
 * onClick = { /* left-click */ },
 * onDoubleClick = { /* double-click */ },
 * onRightClick = { offset -> /* right-click at position */ }
 * )
 * ```
 *
 * @param config Configuration for which click types to detect
 * @param onClick Called on primary (left) click
 * @param onDoubleClick Called on primary double-click
 * @param onRightClick Called on secondary (right) click with the click position
 */
fun Modifier.multiButtonClickable(
    config: ClickConfig = ClickConfig(),
    onClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onRightClick: ((Offset) -> Unit)? = null
): Modifier = this.pointerInput(config, onClick, onDoubleClick, onRightClick) {
    var lastClickTime = 0L
    var lastClickPosition: Offset? = null

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // REPLACED: Use uptimeMillis for KMP compatibility
        val downTime = down.uptimeMillis
        val downPosition = down.position

        // Check for right-click (secondary button)
        if (config.enableSecondaryClick &&
            onRightClick != null &&
            down.pressed &&
            currentEvent.buttons.isSecondaryPressed) {

            // Wait for release to confirm the click
            val up = waitForUpOrCancellation()
            if (up != null) {
                onRightClick(downPosition)
            }
            return@awaitEachGesture
        }

        // Handle primary (left) button
        val up = waitForUpOrCancellation()

        if (up != null) {
            // REPLACED: Use uptimeMillis for KMP compatibility
            val upTime = up.uptimeMillis

            // Check for double-click
            if (config.enableDoubleClick &&
                onDoubleClick != null &&
                lastClickPosition != null) {

                val timeDiff = downTime - lastClickTime
                val distanceDiff = (downPosition - lastClickPosition!!).getDistance()

                // Double-click detected: within time threshold and small position change
                if (timeDiff <= config.doubleClickTimeoutMs && distanceDiff < 50f) {
                    onDoubleClick()
                    // Reset to prevent triple-click being treated as double
                    lastClickTime = 0L
                    lastClickPosition = null
                    return@awaitEachGesture
                }
            }

            // Single primary click
            if (config.enablePrimaryClick && onClick != null) {
                onClick()
            }

            // Store for potential double-click detection
            lastClickTime = upTime
            lastClickPosition = downPosition
        }
    }
}

/**
 * Simplified version for when you only need right-click support alongside
 * existing click handling.
 *
 * This can be combined with `clickable` or used standalone.
 *
 * Usage:
 * ```kotlin
 * Modifier
 * .clickable { /* left-click */ }
 * .onRightClick { offset -> /* show context menu */ }
 * ```
 */
fun Modifier.onRightClick(
    enabled: Boolean = true,
    onRightClick: (Offset) -> Unit
): Modifier = if (!enabled) this else this.pointerInput(onRightClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press &&
                event.buttons.isSecondaryPressed) {
                // Get the click position (local to the composable with this modifier)
                val position = event.changes.firstOrNull()?.position ?: Offset.Zero
                onRightClick(position)
            }
        }
    }
}

/**
 * Combined gesture handler for rows that need both:
 * - Long press + drag for reordering
 * - Regular click/double-click/right-click handling
 *
 * This combines both gesture types in a single pointerInput to avoid conflicts.
 * Uses rememberUpdatedState to ensure callbacks are always up-to-date after recomposition.
 */
@Composable
fun Modifier.draggableRowGestures(
    enabled: Boolean = true,
    longPressTimeoutMs: Long = 400L,
    onLongPressStart: () -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onRightClick: ((Offset) -> Unit)? = null,
    doubleClickTimeoutMs: Long = 400L
): Modifier {
    // Use rememberUpdatedState to ensure callbacks are always current
    // even when pointerInput doesn't recompose
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnDoubleClick by rememberUpdatedState(onDoubleClick)
    val currentOnRightClick by rememberUpdatedState(onRightClick)
    val currentOnLongPressStart by rememberUpdatedState(onLongPressStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    return if (!enabled) this else this.pointerInput(Unit) {
        var lastClickTime = 0L
        var lastClickPosition: Offset? = null

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // REPLACED: Use uptimeMillis
            val downTime = down.uptimeMillis
            val downPosition = down.position

            // Check for right-click (secondary button) using currentEvent which has button state
            val isSecondaryClick = currentEvent.buttons.isSecondaryPressed
            if (currentOnRightClick != null && isSecondaryClick) {
                // Wait for release before invoking callback
                val up = waitForUpOrCancellation()
                if (up != null) {
                    // Use the position from the up event
                    currentOnRightClick?.invoke(up.position)
                }
                return@awaitEachGesture
            }

            var longPressTriggered = false
            var movedTooMuch = false
            var currentPosition = downPosition
            var lastEventTime = downTime // Track time for upTime calculation later

            // Poll for events until we get a release or trigger long press
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: break

                // Track time of current event
                lastEventTime = change.uptimeMillis

                currentPosition = change.position

                // Check if released
                if (!change.pressed) {
                    break
                }

                // Check if moved too much (cancels long press)
                val moved = (currentPosition - downPosition).getDistance()
                if (moved > 15f) {
                    movedTooMuch = true
                    break
                }

                // Check if long press timeout reached
                // REPLACED: Use change.uptimeMillis vs downTime
                val elapsed = change.uptimeMillis - downTime
                if (elapsed >= longPressTimeoutMs) {
                    longPressTriggered = true
                    change.consume()
                    break
                }
            }

            if (longPressTriggered) {
                // Long press detected - start drag mode
                currentOnLongPressStart()

                // Track drag until release
                while (true) {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
                    val change = event.changes.firstOrNull() ?: break

                    if (!change.pressed) {
                        currentOnDragEnd()
                        break
                    }

                    val dragDelta = change.position - change.previousPosition
                    if (dragDelta != Offset.Zero) {
                        change.consume()
                        currentOnDrag(dragDelta)
                    }
                }
                return@awaitEachGesture
            }

            if (movedTooMuch) {
                // User moved before long press - wait for release, don't trigger click
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                }
                return@awaitEachGesture
            }

            // Released before long press - this is a click
            // REPLACED: Use the last event time we tracked
            val upTime = lastEventTime

            // Check for double-click
            if (currentOnDoubleClick != null && lastClickPosition != null) {
                val timeDiff = downTime - lastClickTime
                val distanceDiff = (downPosition - lastClickPosition!!).getDistance()

                if (timeDiff <= doubleClickTimeoutMs && distanceDiff < 50f) {
                    currentOnDoubleClick?.invoke()
                    lastClickTime = 0L
                    lastClickPosition = null
                    return@awaitEachGesture
                }
            }

            // Single click
            currentOnClick?.invoke()
            lastClickTime = upTime
            lastClickPosition = downPosition
        }
    }
}

/**
 * Provides all click information via a single callback with ClickResult.
 * Useful when you want unified click handling logic.
 *
 * Usage:
 * ```kotlin
 * Modifier.detectClicks { result ->
 * when (result.type) {
 * ClickType.PRIMARY -> { /* handle */ }
 * ClickType.SECONDARY -> { showContextMenu(result.position) }
 * ClickType.PRIMARY_DOUBLE -> { /* handle */ }
 * }
 * }
 * ```
 */
fun Modifier.detectClicks(
    config: ClickConfig = ClickConfig(),
    onClickResult: (ClickResult) -> Unit
): Modifier = this.pointerInput(config, onClickResult) {
    var lastClickTime = 0L
    var lastClickPosition: Offset? = null

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // REPLACED: Use uptimeMillis
        val downTime = down.uptimeMillis
        val downPosition = down.position

        // Check for right-click
        if (config.enableSecondaryClick &&
            down.pressed &&
            currentEvent.buttons.isSecondaryPressed) {

            val up = waitForUpOrCancellation()
            if (up != null) {
                onClickResult(ClickResult(ClickType.SECONDARY, downPosition))
            }
            return@awaitEachGesture
        }

        // Handle primary button
        val up = waitForUpOrCancellation()

        if (up != null) {
            // REPLACED: Use uptimeMillis
            val upTime = up.uptimeMillis

            // Check for double-click
            if (config.enableDoubleClick && lastClickPosition != null) {
                val timeDiff = downTime - lastClickTime
                val distanceDiff = (downPosition - lastClickPosition!!).getDistance()

                if (timeDiff <= config.doubleClickTimeoutMs && distanceDiff < 50f) {
                    onClickResult(ClickResult(ClickType.PRIMARY_DOUBLE, downPosition))
                    lastClickTime = 0L
                    lastClickPosition = null
                    return@awaitEachGesture
                }
            }

            // Single primary click
            if (config.enablePrimaryClick) {
                onClickResult(ClickResult(ClickType.PRIMARY, downPosition))
            }

            lastClickTime = upTime
            lastClickPosition = downPosition
        }
    }
}

// ============================================================================
// CONTEXT MENU DATA CLASSES
// ============================================================================

/**
 * Sealed class representing different types of context menu entries.
 * Supports regular items, separators, and submenus.
 */
sealed class ContextMenuEntry {
    /**
     * A regular clickable menu item.
     *
     * @param label Display text for the menu item
     * @param icon Optional leading icon composable
     * @param shortcut Optional keyboard shortcut text (e.g., "⌘C", "Ctrl+C")
     * @param enabled Whether the item is clickable
     * @param onClick Action to perform when clicked
     */
    data class Item(
        val label: String,
        val icon: (@Composable () -> Unit)? = null,
        val shortcut: String? = null,
        val enabled: Boolean = true,
        val onClick: () -> Unit
    ) : ContextMenuEntry()

    /**
     * A visual separator/divider between menu items.
     */
    data object Separator : ContextMenuEntry()

    /**
     * A submenu that expands to show nested items.
     *
     * @param label Display text for the submenu
     * @param icon Optional leading icon composable
     * @param enabled Whether the submenu can be expanded
     * @param items The nested menu entries
     */
    data class SubMenu(
        val label: String,
        val icon: (@Composable () -> Unit)? = null,
        val enabled: Boolean = true,
        val items: List<ContextMenuEntry>
    ) : ContextMenuEntry()
}

/**
 * Legacy ContextMenuItem for backwards compatibility.
 * Prefer using ContextMenuEntry for new code.
 */
data class ContextMenuItem(
    val label: String,
    val icon: (@Composable () -> Unit)? = null,
    val shortcut: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit
) {
    fun toEntry(): ContextMenuEntry.Item = ContextMenuEntry.Item(
        label = label,
        icon = icon,
        shortcut = shortcut,
        enabled = enabled,
        onClick = onClick
    )
}

/**
 * Common context menu actions for scheduler grids.
 */
object SchedulerContextActions {
    const val COPY = "copy"
    const val CUT = "cut"
    const val PASTE = "paste"
    const val DELETE = "delete"
    const val VIEW_HISTORY = "view_history"
    const val VIEW_DETAILS = "view_details"
    const val ADD_SPOT = "add_spot"
    const val MOVE_SPOT = "move_spot"
}

/**
 * Context for a right-click event on a scheduler cell or row.
 *
 * @param rowIndex The row index that was clicked
 * @param columnIndex The column index that was clicked (for grid cells)
 * @param columnId The column ID (for data grids)
 * @param position The screen position of the click (for menu positioning)
 * @param item The data item at the clicked row (if available)
 */
data class RightClickContext<T>(
    val rowIndex: Int,
    val columnIndex: Int = -1,
    val columnId: String? = null,
    val position: Offset,
    val item: T? = null
)

// ============================================================================
// RICH CONTEXT MENU COMPOSABLE
// ============================================================================

/**
 * A rich context menu that supports icons, keyboard shortcuts, separators, and submenus.
 *
 * @param expanded Whether the menu is visible
 * @param onDismissRequest Called when the menu should be dismissed
 * @param entries The list of menu entries to display
 * @param offset Position offset for the menu (use to position at mouse cursor)
 */
@Composable
fun RichContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    entries: ImmutableList<ContextMenuEntry>,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero
) {
    // Track which submenu is currently expanded (by index), -1 means none
    var expandedSubmenuIndex by remember { mutableStateOf(-1) }

    // Track bounds of each entry for pointer position detection
    val entryBounds = remember { mutableStateMapOf<Int, androidx.compose.ui.geometry.Rect>() }

    // Reset expanded submenu when menu closes
    LaunchedEffect(expanded) {
        if (!expanded) {
            expandedSubmenuIndex = -1
            entryBounds.clear()
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .pointerInput(entries) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
                        if (event.type == PointerEventType.Exit && expandedSubmenuIndex == -1) {
                            // Close main menu when mouse exits its bounds (only if no submenu is open)
                            onDismissRequest()
                        } else if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) {
                            val position = event.changes.firstOrNull()?.position ?: continue
                            // Find which entry the pointer is over
                            val hoveredIndex = entryBounds.entries.find { (_, rect) ->
                                rect.contains(position)
                            }?.key

                            if (hoveredIndex != null && hoveredIndex != expandedSubmenuIndex) {
                                val hoveredEntry = entries.getOrNull(hoveredIndex)
                                if (hoveredEntry is ContextMenuEntry.SubMenu && hoveredEntry.enabled) {
                                    // Hovering over a different submenu - expand it
                                    expandedSubmenuIndex = hoveredIndex
                                } else if (hoveredEntry != null && hoveredEntry !is ContextMenuEntry.Separator) {
                                    // Hovering over a regular item - close submenu
                                    expandedSubmenuIndex = -1
                                }
                            }
                        }
                    }
                }
            },
        offset = offset
    ) {
        entries.forEachIndexed { index, entry ->
            RichContextMenuEntry(
                entry = entry,
                entryIndex = index,
                expandedSubmenuIndex = expandedSubmenuIndex,
                onBoundsChanged = { bounds ->
                    entryBounds[index] = bounds
                },
                onSubmenuExpandChange = { expanded ->
                    expandedSubmenuIndex = if (expanded) index else -1
                },
                onDismissRequest = onDismissRequest
            )
        }
    }
}

/**
 * Renders a single context menu entry (item, separator, or submenu).
 */
@Composable
private fun RichContextMenuEntry(
    entry: ContextMenuEntry,
    entryIndex: Int,
    expandedSubmenuIndex: Int,
    onBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit,
    onSubmenuExpandChange: (Boolean) -> Unit,
    onDismissRequest: () -> Unit
) {
    when (entry) {
        is ContextMenuEntry.Item -> {
            Box(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    onBoundsChanged(coordinates.boundsInParent())
                }
            ) {
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = entry.label,
                                color = if (entry.enabled)
                                    LocalContentColor.current
                                else
                                    LocalContentColor.current.copy(alpha = 0.38f)
                            )
                            if (entry.shortcut != null) {
                                Text(
                                    text = entry.shortcut,
                                    fontSize = 12.sp,
                                    color = LocalContentColor.current.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(start = 24.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        onDismissRequest()
                        entry.onClick()
                    },
                    enabled = entry.enabled,
                    leadingIcon = entry.icon?.let { iconContent ->
                        {
                            Box(modifier = Modifier.size(20.dp)) {
                                iconContent()
                            }
                        }
                    }
                )
            }
        }

        is ContextMenuEntry.Separator -> {
            Box(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    onBoundsChanged(coordinates.boundsInParent())
                }
            ) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }

        is ContextMenuEntry.SubMenu -> {
            val isExpanded = expandedSubmenuIndex == entryIndex
            var itemWidth by remember { mutableStateOf(0) }
            var itemPositionX by remember { mutableStateOf(0f) }
            var windowWidth by remember { mutableStateOf(0f) }
            val density = LocalDensity.current

            // Estimated submenu width (typical dropdown menu width)
            val estimatedSubmenuWidth = with(density) { 200.dp.toPx() }

            Box(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    itemWidth = coordinates.size.width
                    // Report bounds to parent for hover detection
                    onBoundsChanged(coordinates.boundsInParent())
                    // Get position in root to calculate available space
                    val positionInRoot = coordinates.positionInRoot()
                    itemPositionX = positionInRoot.x
                    // Get window width from the root coordinates
                    var root = coordinates
                    while (root.parentLayoutCoordinates != null) {
                        root = root.parentLayoutCoordinates!!
                    }
                    windowWidth = root.size.width.toFloat()
                }
            ) {
                // Calculate if submenu should open on the left
                val openOnLeft = remember(itemPositionX, itemWidth, windowWidth) {
                    val rightEdgeAfterSubmenu = itemPositionX + itemWidth + estimatedSubmenuWidth
                    rightEdgeAfterSubmenu > windowWidth && itemPositionX > estimatedSubmenuWidth
                }

                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = entry.label,
                                color = if (entry.enabled)
                                    LocalContentColor.current
                                else
                                    LocalContentColor.current.copy(alpha = 0.38f)
                            )
                            Icon(
                                imageVector = if (openOnLeft)
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft
                                else
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Submenu",
                                modifier = Modifier.size(16.dp),
                                tint = LocalContentColor.current.copy(alpha = 0.6f)
                            )
                        }
                    },
                    onClick = { onSubmenuExpandChange(true) },
                    enabled = entry.enabled,
                    modifier = Modifier.pointerInput(entry.enabled) {
                        if (entry.enabled) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Enter) {
                                        onSubmenuExpandChange(true)
                                    }
                                }
                            }
                        }
                    },
                    leadingIcon = entry.icon?.let { iconContent ->
                        {
                            Box(modifier = Modifier.size(20.dp)) {
                                iconContent()
                            }
                        }
                    }
                )

                // Nested submenu - positioned based on available space
                DropdownMenu(
                    expanded = isExpanded,
                    onDismissRequest = { onSubmenuExpandChange(false) },
                    modifier = Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Exit) {
                                    // Close submenu when mouse exits its bounds
                                    onSubmenuExpandChange(false)
                                }
                            }
                        }
                    },
                    offset = with(density) {
                        if (openOnLeft) {
                            // Position submenu to the left of the parent item
                            DpOffset(x = (-200).dp, y = (-40).dp)
                        } else {
                            // Position submenu to the right of the parent item
                            DpOffset(x = itemWidth.toDp(), y = (-40).dp)
                        }
                    }
                ) {
                    // Nested entries use simplified handling (no hover tracking needed at this level)
                    entry.items.forEach { subEntry ->
                        RichContextMenuEntrySimple(
                            entry = subEntry,
                            onDismissRequest = {
                                onSubmenuExpandChange(false)
                                onDismissRequest()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Simplified entry renderer for nested submenus (without hover tracking).
 */
@Composable
private fun RichContextMenuEntrySimple(
    entry: ContextMenuEntry,
    onDismissRequest: () -> Unit
) {
    when (entry) {
        is ContextMenuEntry.Item -> {
            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.label,
                            color = if (entry.enabled)
                                LocalContentColor.current
                            else
                                LocalContentColor.current.copy(alpha = 0.38f)
                        )
                        if (entry.shortcut != null) {
                            Text(
                                text = entry.shortcut,
                                fontSize = 12.sp,
                                color = LocalContentColor.current.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 24.dp)
                            )
                        }
                    }
                },
                onClick = {
                    onDismissRequest()
                    entry.onClick()
                },
                enabled = entry.enabled,
                leadingIcon = entry.icon?.let { iconContent ->
                    {
                        Box(modifier = Modifier.size(20.dp)) {
                            iconContent()
                        }
                    }
                }
            )
        }

        is ContextMenuEntry.Separator -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        is ContextMenuEntry.SubMenu -> {
            // For nested submenus, use click to expand (simplified behavior)
            var subMenuExpanded by remember { mutableStateOf(false) }
            var itemWidth by remember { mutableStateOf(0) }
            val density = LocalDensity.current

            Box(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    itemWidth = coordinates.size.width
                }
            ) {
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = entry.label,
                                color = if (entry.enabled)
                                    LocalContentColor.current
                                else
                                    LocalContentColor.current.copy(alpha = 0.38f)
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Submenu",
                                modifier = Modifier.size(16.dp),
                                tint = LocalContentColor.current.copy(alpha = 0.6f)
                            )
                        }
                    },
                    onClick = { subMenuExpanded = true },
                    enabled = entry.enabled,
                    leadingIcon = entry.icon?.let { iconContent ->
                        {
                            Box(modifier = Modifier.size(20.dp)) {
                                iconContent()
                            }
                        }
                    }
                )

                DropdownMenu(
                    expanded = subMenuExpanded,
                    onDismissRequest = { subMenuExpanded = false },
                    offset = with(density) {
                        DpOffset(x = itemWidth.toDp(), y = (-40).dp)
                    }
                ) {
                    entry.items.forEach { subEntry ->
                        RichContextMenuEntrySimple(
                            entry = subEntry,
                            onDismissRequest = {
                                subMenuExpanded = false
                                onDismissRequest()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Helper function to convert a list of ContextMenuItem to ContextMenuEntry.
 */
fun List<ContextMenuItem>.toEntries(): List<ContextMenuEntry> = map { it.toEntry() }

// ============================================================================
// RESIZE HANDLE COMPONENT
// ============================================================================

@Composable
fun ColumnResizeHandle(
    modifier: Modifier = Modifier,
    onResize: (Dp) -> Unit
) {
    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .width(10.dp)  // Wider touch target for easier grabbing
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon.Crosshair)  // Use crosshair as resize cursor
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        with(density) {
                            onResize(dragAmount.x.toDp())
                        }
                    }
                )
            }
    ) {
        // Visual indicator - visible line at the edge
        Box(
            modifier = Modifier
                .width(if (isDragging) 3.dp else 2.dp)
                .fillMaxHeight()
                .background(
                    if (isDragging)
                        MaterialTheme.colorScheme.primary
                    else
                        GridColors.resizeHandle.copy(alpha = 0.6f)
                )
                .align(Alignment.CenterEnd)
        )
    }
}

// ============================================================================
// KEYBOARD NAVIGATION HANDLER
// ============================================================================

/**
 * Keyboard event handler for grid navigation
 */
fun Modifier.gridKeyboardNavigation(
    onNavigate: (deltaRow: Int, deltaCol: Int) -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onAdd: () -> Unit,
    onEscape: () -> Unit,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onHome: () -> Unit,
    onEnd: () -> Unit,
    enabled: Boolean = true
): Modifier = this.onPreviewKeyEvent { event ->
    if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

    when (event.key) {
        Key.DirectionUp -> {
            onNavigate(-1, 0)
            true
        }
        Key.DirectionDown -> {
            onNavigate(1, 0)
            true
        }
        Key.DirectionLeft -> {
            onNavigate(0, -1)
            true
        }
        Key.DirectionRight -> {
            onNavigate(0, 1)
            true
        }
        Key.Enter, Key.NumPadEnter -> {
            onSelect()
            true
        }
        Key.Delete, Key.Backspace -> {
            onDelete()
            true
        }
        Key.A -> {
            if (!event.isCtrlPressed) {
                onAdd()
                true
            } else false
        }
        Key.D -> {
            onDelete()
            true
        }
        Key.Escape -> {
            onEscape()
            true
        }
        Key.PageUp -> {
            onPageUp()
            true
        }
        Key.PageDown -> {
            onPageDown()
            true
        }
        Key.MoveHome -> {
            onHome()
            true
        }
        Key.MoveEnd -> {
            onEnd()
            true
        }
        else -> false
    }
}

// ============================================================================
// TOOLTIP WRAPPER
// ============================================================================

/**
 * Simple tooltip wrapper - renders content directly.
 * Note: Platform-specific tooltip implementation can be added using expect/actual.
 */
@Composable
fun TooltipWrapper(
    tooltip: String?,
    content: @Composable () -> Unit
) {
    // For multiplatform compatibility, just render content
    // Tooltips can be implemented per-platform using expect/actual if needed
    content()
}

// ============================================================================
// PERFORMANCE OPTIMIZATIONS
// ============================================================================

/**
 * High-performance cell border drawing using drawBehind instead of Modifier.border.
 * * PERFORMANCE NOTE:
 * Modifier.border creates a new layer for each cell. In a month view with
 * 30 days × 50 breaks = 1500 cells, this causes significant lag during
 * scrolling and resizing.
 * * Using drawBehind draws directly to the canvas without layer overhead,
 * providing smooth scrolling even with thousands of cells.
 * * Usage:
 * ```
 * Box(
 * modifier = Modifier
 * .cellBorder(
 * isSelected = isCellFocused,
 * selectedColor = MaterialTheme.colorScheme.primary,
 * normalColor = GridColors.cellBorder,
 * selectedWidth = 2.dp,
 * normalWidth = 0.5.dp
 * )
 * )
 * ```
 */
fun Modifier.cellBorder(
    isSelected: Boolean,
    selectedColor: Color,
    normalColor: Color = GridColors.cellBorder,
    selectedWidth: Dp = 2.dp,
    normalWidth: Dp = 0.5.dp,
    drawBackground: Color? = null
): Modifier = this.drawBehind {
    // Draw background if specified
    drawBackground?.let { bg ->
        drawRect(color = bg)
    }

    if (isSelected) {
        // Selected: draw full border with thicker stroke
        val strokeWidth = selectedWidth.toPx()
        drawRect(
            color = selectedColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
        )
    } else {
        // Normal: draw only right and bottom borders to avoid double lines
        val strokeWidth = normalWidth.toPx()
        // Right border
        drawLine(
            color = normalColor,
            start = Offset(size.width - strokeWidth / 2, 0f),
            end = Offset(size.width - strokeWidth / 2, size.height),
            strokeWidth = strokeWidth
        )
        // Bottom border
        drawLine(
            color = normalColor,
            start = Offset(0f, size.height - strokeWidth / 2),
            end = Offset(size.width, size.height - strokeWidth / 2),
            strokeWidth = strokeWidth
        )
    }
}

/**
 * Draw cell with background and selection border optimized for grid cells.
 * Combines background drawing with border for single drawBehind pass.
 */
fun Modifier.gridCell(
    backgroundColor: Color,
    isSelected: Boolean,
    selectedBorderColor: Color,
    normalBorderColor: Color = GridColors.cellBorder,
    selectedBorderWidth: Dp = 2.dp,
    normalBorderWidth: Dp = 0.5.dp
): Modifier = this.drawBehind {
    // Draw background
    drawRect(color = backgroundColor)

    // Draw border
    if (isSelected) {
        val strokeWidth = selectedBorderWidth.toPx()
        drawRect(
            color = selectedBorderColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
        )
    } else {
        val strokeWidth = normalBorderWidth.toPx()
        // Right border only (bottom is handled by HorizontalDivider between rows)
        drawLine(
            color = normalBorderColor,
            start = Offset(size.width - strokeWidth / 2, 0f),
            end = Offset(size.width - strokeWidth / 2, size.height),
            strokeWidth = strokeWidth
        )
    }
}

/**
 * Draw only right border - useful for header cells.
 */
fun Modifier.rightBorder(
    color: Color = GridColors.cellBorder,
    width: Dp = 1.dp
): Modifier = this.drawBehind {
    val strokeWidth = width.toPx()
    drawLine(
        color = color,
        start = Offset(size.width - strokeWidth / 2, 0f),
        end = Offset(size.width - strokeWidth / 2, size.height),
        strokeWidth = strokeWidth
    )
}