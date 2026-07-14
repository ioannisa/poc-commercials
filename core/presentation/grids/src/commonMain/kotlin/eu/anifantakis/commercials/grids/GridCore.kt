package eu.anifantakis.commercials.grids

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.collections.immutable.ImmutableList

// ============================================================================
// CORE DATA STRUCTURES
// ============================================================================

/**
 * Bounds for a grid's `scale` knob. The grids are dense, fixed-geometry
 * tools: below [MIN_GRID_SCALE] the cells stop being clickable targets and
 * above [MAX_GRID_SCALE] almost nothing fits on screen without scrolling.
 * Callers are clamped, not trusted.
 */
internal const val MIN_GRID_SCALE = 0.5f
internal const val MAX_GRID_SCALE = 2f

/**
 * The active (already clamped) grid scale, published by each public grid at
 * its own root. The dense type lives in ~30 private composables across this
 * module; a composition local sizes them all without threading a Float
 * through every signature. Static: a scale change must re-lay-out the whole
 * grid anyway, and reads stay free.
 */
internal val LocalGridScale = staticCompositionLocalOf { 1f }

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
 * @param onClick Called on primary (left) click with the keyboard modifiers held at click time
 * @param onDoubleClick Called on primary double-click
 * @param onRightClick Called on secondary (right) click with the click position
 * @param onLongPress Touch/stylus long-press (the finger's "right-click") - gated on the
 *   EVENT's PointerType, never on the platform: a hybrid device gets touch menus from the
 *   finger and right-click menus from the mouse in the same session.
 */
fun Modifier.multiButtonClickable(
    config: ClickConfig = ClickConfig(),
    onClick: ((PointerKeyboardModifiers) -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onRightClick: ((Offset) -> Unit)? = null,
    onLongPress: ((Offset) -> Unit)? = null,
    longPressTimeoutMs: Long = 500L,
): Modifier = this.pointerInput(config, onClick, onDoubleClick, onRightClick, onLongPress) {
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

        // Handle primary (left) button. A stationary touch/stylus hold is the
        // long-press; drag/scroll consumes the pointer, which cancels
        // waitForUpOrCancellation - so scrolling never opens a menu.
        val touchLike = down.type == PointerType.Touch || down.type == PointerType.Stylus
        val up = if (onLongPress != null && touchLike) {
            try {
                withTimeout(longPressTimeoutMs) { waitForUpOrCancellation() }
            } catch (_: PointerEventTimeoutCancellationException) {
                onLongPress(downPosition)
                // Swallow the rest of this gesture so no click fires on lift
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    c.consume()
                }
                return@awaitEachGesture
            }
        } else {
            waitForUpOrCancellation()
        }

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
                onClick(currentEvent.keyboardModifiers)
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
    /** Touch/stylus long-press opens the same menu (per-event PointerType, not platform). */
    onLongPress: ((Offset) -> Unit)? = null,
    onRightClick: (Offset) -> Unit
): Modifier = if (!enabled) this else this
    .pointerInput(onRightClick) {
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
    .then(
        if (onLongPress == null) Modifier
        else Modifier.pointerInput(onLongPress) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val touchLike = down.type == PointerType.Touch || down.type == PointerType.Stylus
                if (!touchLike || currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                try {
                    // Cancelled by scroll/drag consumption; null = lifted early.
                    withTimeout(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                    }
                } catch (_: PointerEventTimeoutCancellationException) {
                    onLongPress(down.position)
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        c.consume()
                    }
                }
            }
        }
    )

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
    onClick: ((PointerKeyboardModifiers) -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onRightClick: ((Offset) -> Unit)? = null,
    /**
     * Touch/stylus context menu: a long-press that is RELEASED WITHOUT
     * DRAGGING opens the menu; a long-press that moves reorders as before.
     * One gesture, two outcomes, no collision - and mouse users keep
     * right-click untouched.
     */
    onLongPress: ((Offset) -> Unit)? = null,
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
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

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
                var totalDrag = 0f
                while (true) {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
                    val change = event.changes.firstOrNull() ?: break

                    if (!change.pressed) {
                        val touchLike = change.type == PointerType.Touch ||
                            change.type == PointerType.Stylus
                        if (touchLike && currentOnLongPress != null && totalDrag < 18f) {
                            // Stationary hold released: the finger's context
                            // menu. Cancel the drag visuals first.
                            currentOnDragCancel()
                            currentOnLongPress?.invoke(downPosition)
                        } else {
                            currentOnDragEnd()
                        }
                        break
                    }

                    val dragDelta = change.position - change.previousPosition
                    if (dragDelta != Offset.Zero) {
                        totalDrag += dragDelta.getDistance()
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
            currentOnClick?.invoke(currentEvent.keyboardModifiers)
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
                        // Hover semantics are MOUSE-ONLY: Compose synthesises
                        // Enter/Exit around taps, so an ungated Exit-dismiss
                        // makes touch menus flicker closed on first contact.
                        val isMouse = event.changes.firstOrNull()?.type == PointerType.Mouse
                        if (event.type == PointerEventType.Exit && isMouse && expandedSubmenuIndex == -1) {
                            // Close main menu when mouse exits its bounds (only if no submenu is open)
                            onDismissRequest()
                        } else if (isMouse && (event.type == PointerEventType.Move || event.type == PointerEventType.Enter)) {
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
                                    style = MaterialTheme.typography.bodySmall,
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
            // Which side the submenu actually opened on, reported back by
            // SubmenuPopup from its measured placement (forward = left in RTL)
            val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            var opensOnLeft by remember { mutableStateOf(isRtl) }

            Box(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    // Report bounds to parent for hover detection
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
                            Icon(
                                // opensOnLeft is a VISUAL side but the icons are
                                // AutoMirrored (render flipped in RTL) — undo the
                                // flip so the chevron points where the submenu opens
                                imageVector = if (opensOnLeft != isRtl)
                                    GridIcons.keyboardArrowLeft
                                else
                                    GridIcons.keyboardArrowRight,
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
                                    // Mouse-only: touch expands via the click
                                    // path; a synthesised Enter must not race it.
                                    if (event.type == PointerEventType.Enter &&
                                        event.changes.firstOrNull()?.type == PointerType.Mouse
                                    ) {
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

                // Nested submenu - anchored to the parent item, flips side/alignment as needed
                SubmenuPopup(
                    expanded = isExpanded,
                    onDismissRequest = { onSubmenuExpandChange(false) },
                    onOpensLeftChange = { opensOnLeft = it }
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
                                style = MaterialTheme.typography.bodySmall,
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
            // Which side the submenu actually opened on, reported back by
            // SubmenuPopup from its measured placement (forward = left in RTL)
            val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            var opensOnLeft by remember { mutableStateOf(isRtl) }

            Box {
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
                                // opensOnLeft is a VISUAL side but the icons are
                                // AutoMirrored (render flipped in RTL) — undo the
                                // flip so the chevron points where the submenu opens
                                imageVector = if (opensOnLeft != isRtl)
                                    GridIcons.keyboardArrowLeft
                                else
                                    GridIcons.keyboardArrowRight,
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

                SubmenuPopup(
                    expanded = subMenuExpanded,
                    onDismissRequest = { subMenuExpanded = false },
                    onOpensLeftChange = { opensOnLeft = it }
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
 * Popup container for nested submenus with classic desktop-menu positioning.
 *
 * Opens "forward" per reading direction — right of the parent item in LTR,
 * left in RTL — flipping to the other side when there is no room, with its
 * first item top-aligned to the parent item. When the submenu would overflow
 * the window bottom, it flips upward so its LAST item stays aligned with the
 * parent item, as native desktop menus do.
 */
@Composable
private fun SubmenuPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onOpensLeftChange: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    if (!expanded) return

    val density = LocalDensity.current
    val currentOnOpensLeftChange by rememberUpdatedState(onOpensLeftChange)
    val positionProvider = remember(density) {
        object : PopupPositionProvider {
            // Matches the menu's internal vertical content padding so that
            // submenu items line up exactly with the parent item
            val menuVerticalPaddingPx = with(density) { 8.dp.roundToPx() }

            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                // Open "forward" per reading direction (right in LTR, left in
                // RTL); flip to the other side only when there is no room.
                val opensLeft = if (layoutDirection == LayoutDirection.Rtl) {
                    anchorBounds.left - popupContentSize.width >= 0
                } else {
                    anchorBounds.right + popupContentSize.width > windowSize.width
                }
                currentOnOpensLeftChange(opensLeft)

                val x = (
                    if (opensLeft) {
                        anchorBounds.left - popupContentSize.width
                    } else {
                        anchorBounds.right
                    }
                ).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))

                val topAligned = anchorBounds.top - menuVerticalPaddingPx
                val bottomAligned = anchorBounds.bottom + menuVerticalPaddingPx - popupContentSize.height
                val y = (
                    if (topAligned + popupContentSize.height <= windowSize.height) {
                        topAligned
                    } else {
                        bottomAligned
                    }
                ).coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height))

                return IntOffset(x, y)
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        // Must NOT be focusable: a focusable popup is modal on desktop and blocks
        // pointer events to the parent menu, making its other items unclickable
        properties = PopupProperties(focusable = false)
    ) {
        Surface(
            shape = MenuDefaults.shape,
            color = MenuDefaults.containerColor,
            tonalElevation = MenuDefaults.TonalElevation,
            shadowElevation = MenuDefaults.ShadowElevation,
            modifier = Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // Mouse-only (see RichContextMenu): a tap's synthesised
                        // Exit must not close the submenu the finger just opened.
                        if (event.type == PointerEventType.Exit &&
                            event.changes.firstOrNull()?.type == PointerType.Mouse
                        ) {
                            // Close submenu when mouse exits its bounds
                            onDismissRequest()
                        }
                    }
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .width(IntrinsicSize.Max)
                    .verticalScroll(rememberScrollState()),
                content = content
            )
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
            // Invisible hit width widens on coarse-pointer sessions (injected
            // slop); the DRAWN line stays hairline either way.
            .width(10.dp + LocalGridInput.current.handleSlop)
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
                        gridPalette().resizeHandle.copy(alpha = 0.6f)
                )
                .align(Alignment.CenterEnd)
        )
    }
}

// ============================================================================
// KEYBOARD NAVIGATION HANDLER
// ============================================================================

/**
 * Keyboard event handler for grid navigation.
 *
 * Pass [isRtl] (`LocalLayoutDirection.current == LayoutDirection.Rtl`) so the
 * horizontal arrow keys move the focus visually — in RTL the columns are laid
 * out mirrored, so visual-left is column index +1.
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
    enabled: Boolean = true,
    isRtl: Boolean = false
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
            onNavigate(0, if (isRtl) 1 else -1)
            true
        }
        Key.DirectionRight -> {
            onNavigate(0, if (isRtl) -1 else 1)
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
 * Cross-platform tooltip: M3's TooltipBox handles hover (desktop/web mouse)
 * AND long-press (touch) in commonMain - one implementation, no expect/actual.
 *
 * CAUTION: TooltipBox's built-in gestures CLAIM the long-press. Use this only
 * where no long-press semantic exists (headers, handles, toolbar buttons);
 * data cells whose long-press opens the context menu must NOT be wrapped.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TooltipWrapper(
    tooltip: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (tooltip.isNullOrBlank()) {
        content()
        return
    }
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults
            .rememberPlainTooltipPositionProvider(),
        tooltip = {
            // PlainTooltip is a TooltipScope member (the lambda's receiver)
            PlainTooltip { Text(tooltip) }
        },
        state = androidx.compose.material3.rememberTooltipState(),
        modifier = modifier,
        content = content,
    )
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
 * normalColor = palette.cellBorder,
 * selectedWidth = 2.dp,
 * normalWidth = 0.5.dp
 * )
 * )
 * ```
 */
fun Modifier.cellBorder(
    isSelected: Boolean,
    selectedColor: Color,
    normalColor: Color = LightGridPalette.cellBorder,
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
    normalBorderColor: Color = LightGridPalette.cellBorder,
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
    color: Color = LightGridPalette.cellBorder,
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