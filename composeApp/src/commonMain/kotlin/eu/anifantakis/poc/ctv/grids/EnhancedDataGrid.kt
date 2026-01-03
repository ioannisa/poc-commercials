package eu.anifantakis.poc.ctv.grids

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

// ============================================================================
// ENHANCED COLUMN DEFINITION WITH EDITING SUPPORT
// ============================================================================

/**
 * Enhanced column definition with inline editing support
 */
@Immutable
data class ColumnDef<T>(
    val id: String,
    val header: String,
    val width: Dp = 120.dp,
    val minWidth: Dp = 40.dp,
    val maxWidth: Dp = 500.dp,
    val resizable: Boolean = true,
    val reorderable: Boolean = true,
    val sortable: Boolean = true,
    val visible: Boolean = true,
    val frozen: FrozenPosition = FrozenPosition.NONE,  // NEW: Sticky column position
    val alignment: TextAlign = TextAlign.Start,
    val headerAlignment: TextAlign = TextAlign.Start,

    // Data extraction
    val extractor: (T) -> String,

    // Styling
    val cellBackground: ((T) -> Color)? = null,
    val cellTextColor: ((T) -> Color)? = null,
    val fontWeight: ((T) -> FontWeight)? = null,
    val comparator: Comparator<T>? = null,
    val cellContent: (@Composable (T, Boolean, Boolean) -> Unit)? = null,  // item, isSelected, isEditing

    // Inline editing (NEW)
    val editable: Boolean = false,
    val editor: (@Composable (T, String, (String) -> Unit, () -> Unit) -> Unit)? = null,  // item, currentValue, onSave, onCancel
    val onValueChange: ((T, String) -> Unit)? = null,  // Called when edit is committed
    val validator: ((String) -> Boolean)? = null  // Validate before commit
)

/**
 * Position for frozen (sticky) columns
 */
enum class FrozenPosition {
    NONE,
    LEFT,   // Frozen to left edge
    RIGHT   // Frozen to right edge
}

/**
 * Configuration for sticky rows
 */
data class StickyRowsConfig(
    val stickyHeader: Boolean = true,           // Freeze header row at top
    val stickyFooter: Boolean = false,          // Freeze totals/footer row at bottom
    val stickyRowCount: Int = 0,                // Number of data rows to freeze at top (after header)
    val stickyBottomRowCount: Int = 0           // Number of data rows to freeze at bottom (before footer)
)

/**
 * Configuration for sticky columns
 */
data class StickyColumnsConfig(
    val freezeRowNumbers: Boolean = true,       // Freeze row number column on left
    val frozenLeftColumns: ImmutableList<String> = persistentListOf(),   // Column IDs to freeze on left
    val frozenRightColumns: ImmutableList<String> = persistentListOf()   // Column IDs to freeze on right
)

// ============================================================================
// EDITING STATE
// ============================================================================

/**
 * Represents the current editing state
 */
data class EditingCell(
    val rowIndex: Int,
    val columnId: String,
    val originalValue: String,
    val currentValue: String
)

@Stable
class EditingState {
    var editingCell by mutableStateOf<EditingCell?>(null)

    fun startEditing(rowIndex: Int, columnId: String, value: String) {
        editingCell = EditingCell(rowIndex, columnId, value, value)
    }

    fun updateValue(value: String) {
        editingCell = editingCell?.copy(currentValue = value)
    }

    fun cancelEditing() {
        editingCell = null
    }

    fun commitEditing(): EditingCell? {
        val cell = editingCell
        editingCell = null
        return cell
    }

    fun isEditing(rowIndex: Int, columnId: String): Boolean {
        return editingCell?.rowIndex == rowIndex && editingCell?.columnId == columnId
    }
}

@Composable
fun rememberEditingState(): EditingState {
    return remember { EditingState() }
}

// ============================================================================
// DRAG STATE FOR VISUAL FEEDBACK
// ============================================================================

/**
 * State for tracking row drag operations with visual feedback
 */
@Stable
class RowDragState {
    var isDragging by mutableStateOf(false)
    var draggedRowIndex by mutableStateOf<Int?>(null)
    var dragOffsetY by mutableStateOf(0f)  // Current Y offset from original position
    var targetDropIndex by mutableStateOf<Int?>(null)  // Where the row will be dropped

    fun startDrag(rowIndex: Int) {
        isDragging = true
        draggedRowIndex = rowIndex
        dragOffsetY = 0f
        targetDropIndex = rowIndex
    }

    fun updateDrag(deltaY: Float, rowHeight: Float, totalRows: Int) {
        dragOffsetY += deltaY
        // Calculate target index based on drag offset
        val draggedIndex = draggedRowIndex ?: return
        val indexOffset = (dragOffsetY / rowHeight).toInt()
        targetDropIndex = (draggedIndex + indexOffset).coerceIn(0, totalRows - 1)
    }

    fun endDrag(): Pair<Int, Int>? {
        val from = draggedRowIndex
        val to = targetDropIndex
        isDragging = false
        draggedRowIndex = null
        dragOffsetY = 0f
        targetDropIndex = null
        return if (from != null && to != null && from != to) Pair(from, to) else null
    }

    fun cancelDrag() {
        isDragging = false
        draggedRowIndex = null
        dragOffsetY = 0f
        targetDropIndex = null
    }
}

/**
 * State for tracking column drag operations with visual feedback
 */
@Stable
class ColumnDragState {
    var isDragging by mutableStateOf(false)
    var draggedColumnId by mutableStateOf<String?>(null)
    var dragOffsetX by mutableStateOf(0f)  // Current X offset from original position
    var targetDropColumnId by mutableStateOf<String?>(null)  // Column to swap with

    fun startDrag(columnId: String) {
        isDragging = true
        draggedColumnId = columnId
        dragOffsetX = 0f
        targetDropColumnId = null
    }

    fun updateDrag(deltaX: Float) {
        dragOffsetX += deltaX
    }

    fun endDrag(): Pair<String, String>? {
        val from = draggedColumnId
        val to = targetDropColumnId
        isDragging = false
        draggedColumnId = null
        dragOffsetX = 0f
        targetDropColumnId = null
        return if (from != null && to != null && from != to) Pair(from, to) else null
    }

    fun cancelDrag() {
        isDragging = false
        draggedColumnId = null
        dragOffsetX = 0f
        targetDropColumnId = null
    }
}

// ============================================================================
// ENHANCED DATAGRID STATE
// ============================================================================

@Stable
class EnhancedDataGridState<T>(
    columns: ImmutableList<ColumnDef<T>>,
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

    // Editing state
    val editingState = EditingState()

    // Drag states for visual feedback
    val rowDragState = RowDragState()
    val columnDragState = ColumnDragState()

    // Legacy drag state (kept for compatibility)
    var draggingRowIndex by mutableStateOf<Int?>(null)

    fun getColumnWidth(columnId: String): Dp {
        return columnStates.find { it.id == columnId }?.width ?: 120.dp
    }

    fun updateColumnWidth(columnId: String, newWidth: Dp, columns: List<ColumnDef<T>>) {
        val colDef = columns.find { it.id == columnId } ?: return
        val clampedWidth = newWidth.coerceIn(colDef.minWidth, colDef.maxWidth)
        columnStates = columnStates.map { state ->
            if (state.id == columnId) state.copy(width = clampedWidth) else state
        }
    }

    fun swapColumns(fromId: String, toId: String) {
        val fromState = columnStates.find { it.id == fromId } ?: return
        val toState = columnStates.find { it.id == toId } ?: return

        val fromIndex = fromState.orderIndex
        val toIndex = toState.orderIndex

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
        // Cancel editing when selecting different row
        if (editingState.editingCell != null && editingState.editingCell?.rowIndex != rowIndex) {
            editingState.cancelEditing()
        }

        when (mode) {
            SelectionMode.NONE -> { }
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
        focusedColumn = -1
    }

    fun updateSelectionAfterReorder(fromIndex: Int, toIndex: Int) {
        // Update selectedRows to reflect the new positions after reorder
        if (selectedRows.isEmpty()) return

        val newSelectedRows = selectedRows.map { selectedIndex ->
            when {
                selectedIndex == fromIndex -> toIndex
                fromIndex < toIndex && selectedIndex in (fromIndex + 1)..toIndex -> selectedIndex - 1
                fromIndex > toIndex && selectedIndex in toIndex until fromIndex -> selectedIndex + 1
                else -> selectedIndex
            }
        }.toSet()

        selectedRows = newSelectedRows

        // Also update focused row
        focusedRow = when {
            focusedRow == fromIndex -> toIndex
            fromIndex < toIndex && focusedRow in (fromIndex + 1)..toIndex -> focusedRow - 1
            fromIndex > toIndex && focusedRow in toIndex until fromIndex -> focusedRow + 1
            else -> focusedRow
        }
    }

    fun startEditing(rowIndex: Int, columnId: String, columns: List<ColumnDef<T>>, items: List<T>) {
        val column = columns.find { it.id == columnId } ?: return
        if (!column.editable) return

        val item = items.getOrNull(rowIndex) ?: return
        val value = column.extractor(item)
        editingState.startEditing(rowIndex, columnId, value)
    }
}

@Composable
fun <T> rememberEnhancedDataGridState(
    columns: ImmutableList<ColumnDef<T>>,
    initialSortColumn: String? = null,
    initialSortDirection: SortDirection = SortDirection.NONE
): EnhancedDataGridState<T> {
    return remember(columns) {
        EnhancedDataGridState(columns, initialSortColumn, initialSortDirection)
    }
}

// ============================================================================
// MAIN ENHANCED DATAGRID COMPONENT
// ============================================================================

/**
 * Enhanced DataGrid with inline editing and sticky rows/columns support.
 *
 * Features:
 * - All features from basic DataGrid
 * - Inline cell editing (F2 or double-click to edit, Enter to save, Escape to cancel)
 * - Sticky header row (always visible at top)
 * - Sticky footer/totals row (always visible at bottom)
 * - Frozen left columns (always visible on left side)
 * - Frozen right columns (always visible on right side)
 * - Frozen row number column
 */
@Composable
fun <T> EnhancedDataGrid(
    items: ImmutableList<T>,
    columns: ImmutableList<ColumnDef<T>>,
    modifier: Modifier = Modifier,
    state: EnhancedDataGridState<T> = rememberEnhancedDataGridState(columns),
    selectionMode: SelectionMode = SelectionMode.SINGLE,

    // Row configuration
    showRowNumbers: Boolean = false,
    rowNumberWidth: Dp = 50.dp,
    rowHeight: Dp = 36.dp,
    headerHeight: Dp = 40.dp,

    // Sticky configuration (NEW)
    stickyRows: StickyRowsConfig = StickyRowsConfig(),
    stickyColumns: StickyColumnsConfig = StickyColumnsConfig(),

    // Editing configuration (NEW)
    enableEditing: Boolean = false,  // Master switch for editing
    onCellValueChanged: ((T, String, String, String) -> Unit)? = null,  // (item, columnId, oldValue, newValue)

    // Reordering configuration (NEW)
    onRowReorder: ((fromIndex: Int, toIndex: Int) -> Unit)? = null,

    // Callbacks
    onRowClick: ((T, Int) -> Unit)? = null,
    onRowDoubleClick: ((T, Int) -> Unit)? = null,
    onRowRightClick: ((T, Int, Offset) -> Unit)? = null,  // Right-click with position for context menu
    contextMenuItems: ((T, Int) -> List<ContextMenuEntry>)? = null,  // Right-click context menu items (supports separators and submenus)
    onSelectionChanged: ((Set<Int>, List<T>) -> Unit)? = null,
    onKeyboardAction: ((KeyboardAction, T?, Int) -> Unit)? = null,
    rowKey: (T) -> Any = { it.hashCode() },

    // Totals row
    totalsRow: (@Composable (List<T>) -> Map<String, String>)? = null,

    // Empty state
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() }
) {
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }

    // Scroll states - need to sync between frozen and scrollable areas
    val verticalScrollState = rememberCachedLazyListState(ahead = 500.dp, behind = 500.dp)
    val horizontalScrollState = rememberScrollState()

    // Separate columns by frozen position
    val (frozenLeftCols, scrollableCols, frozenRightCols) = remember(columns, state.columnStates, stickyColumns) {
        val ordered = columns
            .filter { col -> state.columnStates.find { it.id == col.id }?.visible != false }
            .sortedBy { col -> state.columnStates.find { it.id == col.id }?.orderIndex ?: 0 }

        val left = ordered.filter {
            it.frozen == FrozenPosition.LEFT || it.id in stickyColumns.frozenLeftColumns
        }
        val right = ordered.filter {
            it.frozen == FrozenPosition.RIGHT || it.id in stickyColumns.frozenRightColumns
        }
        val middle = ordered.filter {
            it.frozen == FrozenPosition.NONE &&
                    it.id !in stickyColumns.frozenLeftColumns &&
                    it.id !in stickyColumns.frozenRightColumns
        }

        Triple(left, middle, right)
    }

    // Sort items
    val sortedItems = remember(items, state.sortColumn, state.sortDirection, columns) {
        val sortCol = columns.find { it.id == state.sortColumn }
        if (sortCol == null || state.sortDirection == SortDirection.NONE) {
            items
        } else {
            val comparator = sortCol.comparator ?: Comparator { a, b ->
                sortCol.extractor(a).compareTo(sortCol.extractor(b))
            }
            when (state.sortDirection) {
                SortDirection.ASCENDING -> items.sortedWith(comparator)
                SortDirection.DESCENDING -> items.sortedWith(comparator.reversed())
                SortDirection.NONE -> items
            }
        }
    }

    // Wrapped row reorder callback that also updates selection indices
    val wrappedOnRowReorder: ((Int, Int) -> Unit)? = if (onRowReorder != null) {
        { fromIndex, toIndex ->
            state.updateSelectionAfterReorder(fromIndex, toIndex)
            onRowReorder(fromIndex, toIndex)
        }
    } else null

    // Wrapped right-click callback for external consumers (does NOT handle menu)
    val wrappedOnRowRightClick: ((T, Int, Offset) -> Unit)? = if (onRowRightClick != null) {
        { item, rowIndex, offset ->
            onRowRightClick(item, rowIndex, offset)
        }
    } else null

    // Notify selection changes
    LaunchedEffect(state.selectedRows) {
        val selectedItems = state.selectedRows.mapNotNull { sortedItems.getOrNull(it) }
        onSelectionChanged?.invoke(state.selectedRows, selectedItems)
    }

    // Scroll to focused row
    LaunchedEffect(state.focusedRow) {
        if (state.focusedRow >= 0) {
            verticalScrollState.animateScrollToItem(state.focusedRow)
        }
    }

    // Keyboard handler
    fun handleKeyboard(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (sortedItems.isEmpty()) return false  // Fast exit for empty list

        // If editing, handle edit-specific keys
        if (state.editingState.editingCell != null) {
            return when (event.key) {
                Key.Escape -> {
                    state.editingState.cancelEditing()
                    true
                }
                Key.Enter -> {
                    val cell = state.editingState.commitEditing()
                    if (cell != null && cell.currentValue != cell.originalValue) {
                        val item = sortedItems.getOrNull(cell.rowIndex)
                        if (item != null) {
                            onCellValueChanged?.invoke(item, cell.columnId, cell.originalValue, cell.currentValue)
                        }
                    }
                    true
                }
                Key.Tab -> {
                    // Move to next editable cell
                    val cell = state.editingState.commitEditing()
                    if (cell != null && cell.currentValue != cell.originalValue) {
                        val item = sortedItems.getOrNull(cell.rowIndex)
                        if (item != null) {
                            onCellValueChanged?.invoke(item, cell.columnId, cell.originalValue, cell.currentValue)
                        }
                    }
                    // Find next editable column
                    val allCols = frozenLeftCols + scrollableCols + frozenRightCols
                    val currentIndex = allCols.indexOfFirst { it.id == cell?.columnId }
                    val nextEditableCol = allCols.drop(currentIndex + 1).firstOrNull { it.editable }
                    if (nextEditableCol != null && cell != null) {
                        state.startEditing(cell.rowIndex, nextEditableCol.id, columns, sortedItems)
                    }
                    true
                }
                else -> false  // Let the text field handle other keys
            }
        }

        val currentItem = sortedItems.getOrNull(state.focusedRow)

        return when (event.key) {
            Key.DirectionUp -> {
                if (state.focusedRow > 0) {
                    state.focusedRow--
                    if (selectionMode == SelectionMode.SINGLE) {
                        state.selectedRows = setOf(state.focusedRow)
                    }
                }
                true
            }
            Key.DirectionDown -> {
                if (state.focusedRow < sortedItems.lastIndex) {
                    state.focusedRow++
                    if (selectionMode == SelectionMode.SINGLE) {
                        state.selectedRows = setOf(state.focusedRow)
                    }
                }
                true
            }
            Key.DirectionLeft -> {
                val allCols = frozenLeftCols + scrollableCols + frozenRightCols
                if (state.focusedColumn > 0) {
                    state.focusedColumn--
                }
                true
            }
            Key.DirectionRight -> {
                val allCols = frozenLeftCols + scrollableCols + frozenRightCols
                if (state.focusedColumn < allCols.lastIndex) {
                    state.focusedColumn++
                }
                true
            }
            Key.F2 -> {
                // Start editing current cell
                if (enableEditing && state.focusedRow >= 0 && state.focusedColumn >= 0) {
                    val allCols = frozenLeftCols + scrollableCols + frozenRightCols
                    val col = allCols.getOrNull(state.focusedColumn)
                    if (col != null && col.editable) {
                        state.startEditing(state.focusedRow, col.id, columns, sortedItems)
                    }
                }
                true
            }
            Key.Enter -> {
                if (enableEditing && state.focusedRow >= 0 && state.focusedColumn >= 0) {
                    // Start editing on Enter if cell is editable
                    val allCols = frozenLeftCols + scrollableCols + frozenRightCols
                    val col = allCols.getOrNull(state.focusedColumn)
                    if (col != null && col.editable) {
                        state.startEditing(state.focusedRow, col.id, columns, sortedItems)
                        return true
                    }
                }
                currentItem?.let { onKeyboardAction?.invoke(KeyboardAction.SELECT, it, state.focusedRow) }
                currentItem?.let { onRowDoubleClick?.invoke(it, state.focusedRow) }
                true
            }
            Key.Delete -> {
                currentItem?.let { onKeyboardAction?.invoke(KeyboardAction.DELETE, it, state.focusedRow) }
                true
            }
            Key.Spacebar -> {
                if (selectionMode == SelectionMode.MULTIPLE && state.focusedRow >= 0) {
                    state.selectedRows = if (state.focusedRow in state.selectedRows) {
                        state.selectedRows - state.focusedRow
                    } else {
                        state.selectedRows + state.focusedRow
                    }
                }
                true
            }
            Key.A -> {
                if (event.isCtrlPressed && selectionMode == SelectionMode.MULTIPLE) {
                    state.selectedRows = (0 until sortedItems.size).toSet()
                    true
                } else false
            }
            Key.MoveHome -> {
                state.focusedRow = 0
                state.focusedColumn = 0
                if (selectionMode == SelectionMode.SINGLE) {
                    state.selectedRows = setOf(0)
                }
                true
            }
            Key.MoveEnd -> {
                state.focusedRow = sortedItems.lastIndex.coerceAtLeast(0)
                val allCols = frozenLeftCols + scrollableCols + frozenRightCols
                state.focusedColumn = allCols.lastIndex.coerceAtLeast(0)
                if (selectionMode == SelectionMode.SINGLE) {
                    state.selectedRows = setOf(state.focusedRow)
                }
                true
            }
            Key.PageUp -> {
                state.focusedRow = (state.focusedRow - 10).coerceAtLeast(0)
                if (selectionMode == SelectionMode.SINGLE) {
                    state.selectedRows = setOf(state.focusedRow)
                }
                true
            }
            Key.PageDown -> {
                state.focusedRow = (state.focusedRow + 10).coerceAtMost(sortedItems.lastIndex.coerceAtLeast(0))
                if (selectionMode == SelectionMode.SINGLE) {
                    state.selectedRows = setOf(state.focusedRow)
                }
                true
            }
            else -> false
        }
    }

    // Calculate widths for frozen sections
    val frozenLeftWidth = remember(frozenLeftCols, state.columnStates, showRowNumbers, rowNumberWidth, stickyColumns) {
        var width = if (showRowNumbers && stickyColumns.freezeRowNumbers) rowNumberWidth else 0.dp
        frozenLeftCols.forEach { col -> width += state.getColumnWidth(col.id) }
        width
    }

    val frozenRightWidth = remember(frozenRightCols, state.columnStates) {
        var width = 0.dp
        frozenRightCols.forEach { col -> width += state.getColumnWidth(col.id) }
        width
    }

    // Main layout using Box for layered sticky regions
    Box(
        modifier = modifier
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
            .border(1.dp, if (hasFocus) MaterialTheme.colorScheme.primary else GridColors.headerBorder)
    ) {
        if (sortedItems.isEmpty()) {
            // Empty state
            Column(modifier = Modifier.fillMaxSize()) {
                // Still show header
                StickyHeaderRow(
                    columns = (frozenLeftCols + scrollableCols + frozenRightCols).toImmutableList(),
                    state = state,
                    showRowNumbers = showRowNumbers,
                    rowNumberWidth = rowNumberWidth,
                    headerHeight = headerHeight,
                    horizontalScrollState = horizontalScrollState,
                    frozenLeftWidth = frozenLeftWidth,
                    frozenRightWidth = frozenRightWidth,
                    frozenLeftCols = frozenLeftCols.toImmutableList(),
                    frozenRightCols = frozenRightCols.toImmutableList(),
                    scrollableCols = scrollableCols.toImmutableList(),
                    stickyColumns = stickyColumns,
                    allColumns = columns,
                    onHeaderReorder = { fromId, toId -> state.swapColumns(fromId, toId) }
                )

                HorizontalDivider(thickness = 2.dp, color = GridColors.headerBorder)

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    emptyContent()
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // ===== STICKY HEADER =====
                if (stickyRows.stickyHeader) {
                    StickyHeaderRow(
                        columns = (frozenLeftCols + scrollableCols + frozenRightCols).toImmutableList(),
                        state = state,
                        showRowNumbers = showRowNumbers,
                        rowNumberWidth = rowNumberWidth,
                        headerHeight = headerHeight,
                        horizontalScrollState = horizontalScrollState,
                        frozenLeftWidth = frozenLeftWidth,
                        frozenRightWidth = frozenRightWidth,
                        frozenLeftCols = frozenLeftCols.toImmutableList(),
                        frozenRightCols = frozenRightCols.toImmutableList(),
                        scrollableCols = scrollableCols.toImmutableList(),
                        stickyColumns = stickyColumns,
                        allColumns = columns,
                        onHeaderReorder = { fromId, toId -> state.swapColumns(fromId, toId) }
                    )

                    HorizontalDivider(thickness = 2.dp, color = GridColors.headerBorder)
                }

                // ===== SCROLLABLE BODY =====
                Box(modifier = Modifier.weight(1f)) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Frozen left section (including row numbers)
                        if (frozenLeftWidth > 0.dp) {
                            FrozenLeftSection(
                                items = sortedItems.toImmutableList(),
                                columns = frozenLeftCols.toImmutableList(),
                                state = state,
                                showRowNumbers = showRowNumbers && stickyColumns.freezeRowNumbers,
                                rowNumberWidth = rowNumberWidth,
                                rowHeight = rowHeight,
                                verticalScrollState = verticalScrollState,
                                hasFocus = hasFocus,
                                enableEditing = enableEditing,
                                onCellValueChanged = onCellValueChanged,
                                selectionMode = selectionMode,
                                onRowClick = onRowClick,
                                onRowDoubleClick = onRowDoubleClick,
                                onRowRightClick = wrappedOnRowRightClick,
                                contextMenuItems = contextMenuItems, // Pass menu items down
                                rowKey = rowKey,
                                allColumns = columns,
                                allItems = sortedItems.toImmutableList(),
                                onRowReorder = wrappedOnRowReorder
                            )

                            // Shadow/divider for frozen left
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .background(GridColors.headerBorder)
                            )
                        }

                        // Scrollable middle section
                        Box(modifier = Modifier.weight(1f)) {
                            ScrollableMiddleSection(
                                items = sortedItems.toImmutableList(),
                                columns = scrollableCols.toImmutableList(),
                                state = state,
                                showRowNumbers = showRowNumbers && !stickyColumns.freezeRowNumbers,
                                rowNumberWidth = rowNumberWidth,
                                rowHeight = rowHeight,
                                verticalScrollState = verticalScrollState,
                                horizontalScrollState = horizontalScrollState,
                                hasFocus = hasFocus,
                                enableEditing = enableEditing,
                                onCellValueChanged = onCellValueChanged,
                                selectionMode = selectionMode,
                                onRowClick = onRowClick,
                                onRowDoubleClick = onRowDoubleClick,
                                onRowRightClick = wrappedOnRowRightClick,
                                contextMenuItems = contextMenuItems, // Pass menu items down
                                rowKey = rowKey,
                                allColumns = columns,
                                allItems = sortedItems.toImmutableList(),
                                frozenLeftCols = frozenLeftCols.toImmutableList(),
                                onRowReorder = wrappedOnRowReorder
                            )

                            // Scrollbars (platform-specific - shown on desktop/web, hidden on mobile)
                            PlatformVerticalScrollbar(
                                lazyListState = verticalScrollState,
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                            )

                            PlatformHorizontalScrollbar(
                                scrollState = horizontalScrollState,
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            )
                        }

                        // Frozen right section
                        if (frozenRightWidth > 0.dp) {
                            // Shadow/divider for frozen right
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .background(GridColors.headerBorder)
                            )

                            FrozenRightSection(
                                items = sortedItems.toImmutableList(),
                                columns = frozenRightCols.toImmutableList(),
                                state = state,
                                rowHeight = rowHeight,
                                verticalScrollState = verticalScrollState,
                                hasFocus = hasFocus,
                                enableEditing = enableEditing,
                                onCellValueChanged = onCellValueChanged,
                                selectionMode = selectionMode,
                                onRowClick = onRowClick,
                                onRowDoubleClick = onRowDoubleClick,
                                onRowRightClick = wrappedOnRowRightClick,
                                contextMenuItems = contextMenuItems, // Pass menu items down
                                rowKey = rowKey,
                                allColumns = columns,
                                allItems = sortedItems.toImmutableList(),
                                frozenLeftCols = frozenLeftCols.toImmutableList(),
                                scrollableCols = scrollableCols.toImmutableList(),
                                onRowReorder = wrappedOnRowReorder
                            )
                        }
                    }
                }

                // ===== STICKY FOOTER (TOTALS) =====
                if (stickyRows.stickyFooter && totalsRow != null) {
                    HorizontalDivider(thickness = 2.dp, color = GridColors.headerBorder)

                    StickyFooterRow(
                        totals = totalsRow(sortedItems),
                        columns = (frozenLeftCols + scrollableCols + frozenRightCols).toImmutableList(),
                        state = state,
                        showRowNumbers = showRowNumbers,
                        rowNumberWidth = rowNumberWidth,
                        rowHeight = rowHeight,
                        horizontalScrollState = horizontalScrollState,
                        frozenLeftWidth = frozenLeftWidth,
                        frozenRightWidth = frozenRightWidth,
                        frozenLeftCols = frozenLeftCols.toImmutableList(),
                        frozenRightCols = frozenRightCols.toImmutableList(),
                        scrollableCols = scrollableCols.toImmutableList(),
                        stickyColumns = stickyColumns
                    )
                }
            }
        }
    }
}

// ... StickyHeaderRow and HeaderCell implementation remains the same ...
// (Included for context, but logic unchanged)
@Composable
private fun <T> StickyHeaderRow(
    columns: ImmutableList<ColumnDef<T>>,
    state: EnhancedDataGridState<T>,
    showRowNumbers: Boolean,
    rowNumberWidth: Dp,
    headerHeight: Dp,
    horizontalScrollState: ScrollState,
    frozenLeftWidth: Dp,
    frozenRightWidth: Dp,
    frozenLeftCols: ImmutableList<ColumnDef<T>>,
    frozenRightCols: ImmutableList<ColumnDef<T>>,
    scrollableCols: ImmutableList<ColumnDef<T>>,
    stickyColumns: StickyColumnsConfig,
    allColumns: ImmutableList<ColumnDef<T>>,
    onHeaderReorder: (String, String) -> Unit
) {
    val columnDragState = state.columnDragState

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .background(GridColors.headerBackground)
    ) {
        // Frozen left header
        if (frozenLeftWidth > 0.dp) {
            Row(modifier = Modifier.width(frozenLeftWidth)) {
                if (showRowNumbers && stickyColumns.freezeRowNumbers) {
                    HeaderCell<T>(
                        text = "#",
                        width = rowNumberWidth,
                        isSorted = false,
                        sortDirection = SortDirection.NONE,
                        onSort = { },
                        onResize = null,
                        columnId = "",
                        allColumns = emptyList<ColumnDef<T>>().toImmutableList(),
                        onReorder = null,
                        columnDragState = null
                    )
                }

                frozenLeftCols.forEach { column ->
                    val width = state.getColumnWidth(column.id)
                    HeaderCell(
                        text = column.header,
                        width = width,
                        isSorted = state.sortColumn == column.id,
                        sortDirection = if (state.sortColumn == column.id) state.sortDirection else SortDirection.NONE,
                        onSort = { if (column.sortable) state.toggleSort(column.id) },
                        onResize = if (column.resizable) { delta ->
                            val currentWidth = state.getColumnWidth(column.id)
                            state.updateColumnWidth(column.id, currentWidth + delta, allColumns)
                        } else null,
                        columnId = column.id,
                        allColumns = frozenLeftCols,
                        onReorder = if (column.reorderable) onHeaderReorder else null,
                        columnDragState = if (column.reorderable) columnDragState else null
                    )
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(GridColors.headerBorder)
            )
        }

        // Scrollable header
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(horizontalScrollState)
        ) {
            if (showRowNumbers && !stickyColumns.freezeRowNumbers) {
                HeaderCell<T>(
                    text = "#",
                    width = rowNumberWidth,
                    isSorted = false,
                    sortDirection = SortDirection.NONE,
                    onSort = { },
                    onResize = null,
                    columnId = "",
                    allColumns = emptyList<ColumnDef<T>>().toImmutableList(),
                    onReorder = null,
                    columnDragState = null
                )
            }

            scrollableCols.forEach { column ->
                val width = state.getColumnWidth(column.id)
                HeaderCell(
                    text = column.header,
                    width = width,
                    isSorted = state.sortColumn == column.id,
                    sortDirection = if (state.sortColumn == column.id) state.sortDirection else SortDirection.NONE,
                    onSort = { if (column.sortable) state.toggleSort(column.id) },
                    onResize = if (column.resizable) { delta ->
                        val currentWidth = state.getColumnWidth(column.id)
                        state.updateColumnWidth(column.id, currentWidth + delta, allColumns)
                    } else null,
                    columnId = column.id,
                    allColumns = scrollableCols,
                    onReorder = if (column.reorderable) onHeaderReorder else null,
                    columnDragState = if (column.reorderable) columnDragState else null
                )
            }
        }

        // Frozen right header
        if (frozenRightWidth > 0.dp) {
            // Divider
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(GridColors.headerBorder)
            )

            Row(modifier = Modifier.width(frozenRightWidth)) {
                frozenRightCols.forEach { column ->
                    val width = state.getColumnWidth(column.id)
                    HeaderCell(
                        text = column.header,
                        width = width,
                        isSorted = state.sortColumn == column.id,
                        sortDirection = if (state.sortColumn == column.id) state.sortDirection else SortDirection.NONE,
                        onSort = { if (column.sortable) state.toggleSort(column.id) },
                        onResize = if (column.resizable) { delta ->
                            val currentWidth = state.getColumnWidth(column.id)
                            state.updateColumnWidth(column.id, currentWidth + delta, allColumns)
                        } else null,
                        columnId = column.id,
                        allColumns = frozenRightCols,
                        onReorder = if (column.reorderable) onHeaderReorder else null,
                        columnDragState = if (column.reorderable) columnDragState else null
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> HeaderCell(
    text: String,
    width: Dp,
    isSorted: Boolean,
    sortDirection: SortDirection,
    onSort: () -> Unit,
    onResize: ((Dp) -> Unit)?,
    columnId: String,
    allColumns: ImmutableList<ColumnDef<T>>,
    onReorder: ((String, String) -> Unit)?,
    columnDragState: ColumnDragState? = null
) {
    val density = LocalDensity.current
    val isDragging = columnDragState?.isDragging == true && columnDragState.draggedColumnId == columnId
    val isDropTarget = columnDragState?.isDragging == true &&
            columnDragState.targetDropColumnId == columnId &&
            columnDragState.draggedColumnId != columnId

    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                // Visual feedback for dragging
                if (isDragging) {
                    translationX = columnDragState.dragOffsetX
                    shadowElevation = 8f
                    scaleY = 1.05f
                    alpha = 0.9f
                }
            }
            .background(
                when {
                    isDragging -> MaterialTheme.colorScheme.primaryContainer
                    isDropTarget -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    else -> Color.Transparent
                }
            )
            .let { m ->
                if (onReorder != null && allColumns.isNotEmpty() && columnDragState != null) {
                    m.pointerInput(allColumns, columnDragState) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                columnDragState.startDrag(columnId)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                columnDragState.updateDrag(dragAmount.x)

                                // Calculate which column we're hovering over
                                val currentIndex = allColumns.indexOfFirst { it.id == columnId }
                                if (currentIndex != -1) {
                                    val dragOffsetX = columnDragState.dragOffsetX
                                    var accumulatedWidth = 0f

                                    // Calculate target column based on drag position
                                    if (dragOffsetX > 0) {
                                        // Dragging right
                                        for (i in currentIndex + 1..allColumns.lastIndex) {
                                            val colWidth = with(density) { width.toPx() } // Simplified - use current width
                                            accumulatedWidth += colWidth
                                            if (dragOffsetX < accumulatedWidth - colWidth / 2) {
                                                // Safety check: ensure i - 1 is valid
                                                if (i - 1 >= 0 && i - 1 < allColumns.size) {
                                                    columnDragState.targetDropColumnId = allColumns[i - 1].id
                                                }
                                                break
                                            }
                                            // Safety check: ensure i is valid
                                            if (i < allColumns.size) {
                                                columnDragState.targetDropColumnId = allColumns[i].id
                                            }
                                        }
                                    } else {
                                        // Dragging left
                                        for (i in currentIndex - 1 downTo 0) {
                                            val colWidth = with(density) { width.toPx() }
                                            accumulatedWidth -= colWidth
                                            if (dragOffsetX > accumulatedWidth + colWidth / 2) {
                                                // Safety check: ensure i + 1 is valid
                                                if (i + 1 >= 0 && i + 1 < allColumns.size) {
                                                    columnDragState.targetDropColumnId = allColumns[i + 1].id
                                                }
                                                break
                                            }
                                            // Safety check: ensure i is valid
                                            if (i >= 0 && i < allColumns.size) {
                                                columnDragState.targetDropColumnId = allColumns[i].id
                                            }
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                val result = columnDragState.endDrag()
                                if (result != null) {
                                    onReorder(result.first, result.second)
                                }
                            },
                            onDragCancel = {
                                columnDragState.cancelDrag()
                            }
                        )
                    }
                } else m
            }
            .clickable { onSort() }
    ) {
        // Drop indicator line on left side
        if (isDropTarget) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
                    .align(Alignment.CenterStart)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            if (isSorted && sortDirection != SortDirection.NONE) {
                Icon(
                    imageVector = if (sortDirection == SortDirection.ASCENDING)
                        Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).padding(start = 4.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Resize handle
        if (onResize != null) {
            ColumnResizeHandle(
                modifier = Modifier.align(Alignment.CenterEnd),
                onResize = onResize
            )
        }

        // Right border
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(GridColors.headerBorder)
                .align(Alignment.CenterEnd)
        )
    }
}

// ============================================================================
// FROZEN LEFT SECTION
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> FrozenLeftSection(
    items: ImmutableList<T>,
    columns: ImmutableList<ColumnDef<T>>,
    state: EnhancedDataGridState<T>,
    showRowNumbers: Boolean,
    rowNumberWidth: Dp,
    rowHeight: Dp,
    verticalScrollState: LazyListState,
    hasFocus: Boolean,
    enableEditing: Boolean,
    onCellValueChanged: ((T, String, String, String) -> Unit)?,
    selectionMode: SelectionMode,
    onRowClick: ((T, Int) -> Unit)?,
    onRowDoubleClick: ((T, Int) -> Unit)?,
    onRowRightClick: ((T, Int, Offset) -> Unit)?,
    contextMenuItems: ((T, Int) -> List<ContextMenuEntry>)?, 
    rowKey: (T) -> Any,
    allColumns: ImmutableList<ColumnDef<T>>,
    allItems: ImmutableList<T>,
    onRowReorder: ((Int, Int) -> Unit)?
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val rowDragState = state.rowDragState

    CachedLazyColumn(
        state = verticalScrollState,
        modifier = Modifier.fillMaxHeight()
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> rowKey(item) }
        ) { rowIndex, item ->
            // PERFORMANCE: Wrap T in StableItem<T> to allow Compose compiler to skip recomposition
            val stableItem = StableItem(item)

            FrozenLeftRow(
                itemWrapper = stableItem,
                rowIndex = rowIndex,
                columns = columns,
                state = state,
                showRowNumbers = showRowNumbers,
                rowNumberWidth = rowNumberWidth,
                rowHeight = rowHeight,
                hasFocus = hasFocus,
                enableEditing = enableEditing,
                onCellValueChanged = onCellValueChanged,
                selectionMode = selectionMode,
                onClick = {
                    state.selectRow(rowIndex, selectionMode)
                    onRowClick?.invoke(item, rowIndex)
                },
                onDoubleClick = {
                    onRowDoubleClick?.invoke(item, rowIndex)
                },
                onRightClick = { offset ->
                    state.selectRow(rowIndex, selectionMode)
                    onRowRightClick?.invoke(item, rowIndex, offset)
                },
                contextMenuItems = contextMenuItems,
                allColumns = allColumns,
                allItems = allItems,
                columnOffset = 0,
                rowDragState = rowDragState,
                rowHeightPx = rowHeightPx,
                totalRows = items.size,
                onRowReorder = onRowReorder
            )

            HorizontalDivider(color = GridColors.cellBorder)
        }
    }
}

@Composable
private fun <T> FrozenLeftRow(
    itemWrapper: StableItem<T>,
    rowIndex: Int,
    columns: ImmutableList<ColumnDef<T>>,
    state: EnhancedDataGridState<T>,
    showRowNumbers: Boolean,
    rowNumberWidth: Dp,
    rowHeight: Dp,
    hasFocus: Boolean,
    enableEditing: Boolean,
    onCellValueChanged: ((T, String, String, String) -> Unit)?,
    selectionMode: SelectionMode,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onRightClick: ((Offset) -> Unit)?,
    contextMenuItems: ((T, Int) -> List<ContextMenuEntry>)?,
    allColumns: ImmutableList<ColumnDef<T>>,
    allItems: ImmutableList<T>,
    columnOffset: Int,
    rowDragState: RowDragState? = null,
    rowHeightPx: Float = 0f,
    totalRows: Int = 0,
    onRowReorder: ((Int, Int) -> Unit)? = null
) {
    // Extract actual item from stable wrapper
    val item = itemWrapper.value

    val isSelected = rowIndex in state.selectedRows
    val isFocused = rowIndex == state.focusedRow && hasFocus
    val isDragging = rowDragState?.isDragging == true && rowDragState.draggedRowIndex == rowIndex
    val isDropTarget = rowDragState?.isDragging == true &&
            rowDragState.targetDropIndex == rowIndex &&
            rowDragState.draggedRowIndex != rowIndex

    // Local menu state for right-click handling
    var menuVisible by remember { mutableStateOf(false) }
    var clickOffset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    val backgroundColor = when {
        isDragging -> MaterialTheme.colorScheme.primaryContainer
        isDropTarget -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        isFocused && isSelected -> GridColors.rowFocused
        isSelected -> GridColors.rowSelected
        rowIndex % 2 == 1 -> GridColors.rowAlternate
        else -> Color.White
    }

    Row(
        modifier = Modifier
            .height(rowHeight)
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                if (isDragging) {
                    translationY = rowDragState.dragOffsetY
                    shadowElevation = 12f
                    scaleX = 1.02f
                    alpha = 0.95f
                }
            }
            .background(backgroundColor)
            // Use separate onRightClick modifier for reliable right-click detection
            .let { mod ->
                mod.onRightClick { offset ->
                    // Update local state for menu
                    clickOffset = offset
                    menuVisible = true

                    // Call parent handler
                    onRightClick?.invoke(offset)
                }
            }
            .draggableRowGestures(
                enabled = true,
                onLongPressStart = {
                    if (onRowReorder != null && rowDragState != null) {
                        rowDragState.startDrag(rowIndex)
                    }
                },
                onDrag = { dragDelta ->
                    if (onRowReorder != null && rowDragState != null) {
                        rowDragState.updateDrag(dragDelta.y, rowHeightPx, totalRows)
                    }
                },
                onDragEnd = {
                    if (onRowReorder != null && rowDragState != null) {
                        val result = rowDragState.endDrag()
                        if (result != null) {
                            onRowReorder(result.first, result.second)
                        }
                    }
                },
                onDragCancel = {
                    rowDragState?.cancelDrag()
                },
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onRightClick = null  // Right-click handled by separate modifier above
            )
    ) {
        // Row number
        if (showRowNumbers) {
            Box(
                modifier = Modifier
                    .width(rowNumberWidth)
                    .fillMaxHeight()
                    .background(GridColors.headerBackground.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (rowIndex + 1).toString(),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }

        // Data cells
        columns.forEachIndexed { colIndex, column ->
            DataCell(
                itemWrapper = itemWrapper,
                rowIndex = rowIndex,
                column = column,
                columnIndex = columnOffset + colIndex,
                state = state,
                hasFocus = hasFocus,
                enableEditing = enableEditing,
                onCellValueChanged = onCellValueChanged,
                allColumns = allColumns,
                allItems = allItems,
                onCellClick = onClick,
                onCellRightClick = { offset ->
                    clickOffset = offset
                    menuVisible = true
                    onRightClick?.invoke(offset)
                }
            )
        }

        // Render Context Menu LOCALLY inside the Row
        // This ensures positioning is relative to this row, fixing the scroll bug
        if (menuVisible && contextMenuItems != null) {
            val entries = contextMenuItems(item, rowIndex)
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

// ============================================================================
// SCROLLABLE MIDDLE SECTION
// ============================================================================

@Composable
private fun <T> ScrollableMiddleSection(
    items: ImmutableList<T>,
    columns: ImmutableList<ColumnDef<T>>,
    state: EnhancedDataGridState<T>,
    showRowNumbers: Boolean,
    rowNumberWidth: Dp,
    rowHeight: Dp,
    verticalScrollState: LazyListState,
    horizontalScrollState: ScrollState,
    hasFocus: Boolean,
    enableEditing: Boolean,
    onCellValueChanged: ((T, String, String, String) -> Unit)?,
    selectionMode: SelectionMode,
    onRowClick: ((T, Int) -> Unit)?,
    onRowDoubleClick: ((T, Int) -> Unit)?,
    onRowRightClick: ((T, Int, Offset) -> Unit)?,
    contextMenuItems: ((T, Int) -> List<ContextMenuEntry>)?,
    rowKey: (T) -> Any,
    allColumns: ImmutableList<ColumnDef<T>>,
    allItems: ImmutableList<T>,
    frozenLeftCols: ImmutableList<ColumnDef<T>>,
    onRowReorder: ((Int, Int) -> Unit)?
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val rowDragState = state.rowDragState

    CachedLazyColumn(
        state = verticalScrollState,
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(horizontalScrollState)
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> rowKey(item) }
        ) { rowIndex, item ->
            // PERFORMANCE: Wrap T in StableItem<T> to allow Compose compiler to skip recomposition
            val stableItem = StableItem(item)

            ScrollableMiddleRow(
                itemWrapper = stableItem,
                rowIndex = rowIndex,
                columns = columns,
                state = state,
                showRowNumbers = showRowNumbers,
                rowNumberWidth = rowNumberWidth,
                rowHeight = rowHeight,
                hasFocus = hasFocus,
                enableEditing = enableEditing,
                onCellValueChanged = onCellValueChanged,
                selectionMode = selectionMode,
                onClick = {
                    state.selectRow(rowIndex, selectionMode)
                    onRowClick?.invoke(item, rowIndex)
                },
                onDoubleClick = {
                    onRowDoubleClick?.invoke(item, rowIndex)
                },
                onRightClick = { offset ->
                    state.selectRow(rowIndex, selectionMode)
                    onRowRightClick?.invoke(item, rowIndex, offset)
                },
                contextMenuItems = contextMenuItems,
                allColumns = allColumns,
                allItems = allItems,
                columnOffset = frozenLeftCols.size + (if (showRowNumbers) 1 else 0),
                rowDragState = rowDragState,
                rowHeightPx = rowHeightPx,
                totalRows = items.size,
                onRowReorder = onRowReorder
            )

            HorizontalDivider(color = GridColors.cellBorder)
        }
    }
}

@Composable
private fun <T> ScrollableMiddleRow(
    itemWrapper: StableItem<T>,
    rowIndex: Int,
    columns: ImmutableList<ColumnDef<T>>,
    state: EnhancedDataGridState<T>,
    showRowNumbers: Boolean,
    rowNumberWidth: Dp,
    rowHeight: Dp,
    hasFocus: Boolean,
    enableEditing: Boolean,
    onCellValueChanged: ((T, String, String, String) -> Unit)?,
    selectionMode: SelectionMode,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onRightClick: ((Offset) -> Unit)?,
    contextMenuItems: ((T, Int) -> List<ContextMenuEntry>)?,
    allColumns: ImmutableList<ColumnDef<T>>,
    allItems: ImmutableList<T>,
    columnOffset: Int,
    rowDragState: RowDragState? = null,
    rowHeightPx: Float = 0f,
    totalRows: Int = 0,
    onRowReorder: ((Int, Int) -> Unit)? = null
) {
    // Extract actual item from stable wrapper
    val item = itemWrapper.value

    val isSelected = rowIndex in state.selectedRows
    val isFocused = rowIndex == state.focusedRow && hasFocus
    val isDragging = rowDragState?.isDragging == true && rowDragState.draggedRowIndex == rowIndex
    val isDropTarget = rowDragState?.isDragging == true &&
            rowDragState.targetDropIndex == rowIndex &&
            rowDragState.draggedRowIndex != rowIndex

    // Local menu state for right-click handling
    var menuVisible by remember { mutableStateOf(false) }
    var clickOffset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    val backgroundColor = when {
        isDragging -> MaterialTheme.colorScheme.primaryContainer
        isDropTarget -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        isFocused && isSelected -> GridColors.rowFocused
        isSelected -> GridColors.rowSelected
        rowIndex % 2 == 1 -> GridColors.rowAlternate
        else -> Color.White
    }

    Row(
        modifier = Modifier
            .height(rowHeight)
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                if (isDragging) {
                    translationY = rowDragState.dragOffsetY
                    shadowElevation = 12f
                    scaleX = 1.02f
                    alpha = 0.95f
                }
            }
            .background(backgroundColor)
            // Use separate onRightClick modifier for reliable right-click detection
            .let { mod ->
                mod.onRightClick { offset ->
                    // Update local state for menu
                    clickOffset = offset
                    menuVisible = true

                    // Call parent handler
                    onRightClick?.invoke(offset)
                }
            }
            .draggableRowGestures(
                enabled = true,
                onLongPressStart = {
                    if (onRowReorder != null && rowDragState != null) {
                        rowDragState.startDrag(rowIndex)
                    }
                },
                onDrag = { dragDelta ->
                    if (onRowReorder != null && rowDragState != null) {
                        rowDragState.updateDrag(dragDelta.y, rowHeightPx, totalRows)
                    }
                },
                onDragEnd = {
                    if (onRowReorder != null && rowDragState != null) {
                        val result = rowDragState.endDrag()
                        if (result != null) {
                            onRowReorder(result.first, result.second)
                        }
                    }
                },
                onDragCancel = {
                    rowDragState?.cancelDrag()
                },
                onClick = onClick,
                onDoubleClick = {
                    // Check if we should start editing
                    if (enableEditing && state.focusedColumn >= columnOffset) {
                        val localColIndex = state.focusedColumn - columnOffset
                        val col = columns.getOrNull(localColIndex)
                        if (col != null && col.editable) {
                            state.startEditing(rowIndex, col.id, allColumns, allItems)
                            return@draggableRowGestures
                        }
                    }
                    onDoubleClick()
                },
                onRightClick = null  // Right-click handled by separate modifier above
            )
    ) {
        // Row number (if not frozen)
        if (showRowNumbers) {
            Box(
                modifier = Modifier
                    .width(rowNumberWidth)
                    .fillMaxHeight()
                    .background(GridColors.headerBackground.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (rowIndex + 1).toString(),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }

        // Data cells
        columns.forEachIndexed { colIndex, column ->
            DataCell(
                itemWrapper = itemWrapper,
                rowIndex = rowIndex,
                column = column,
                columnIndex = columnOffset + colIndex,
                state = state,
                hasFocus = hasFocus,
                enableEditing = enableEditing,
                onCellValueChanged = onCellValueChanged,
                allColumns = allColumns,
                allItems = allItems,
                onCellClick = onClick,
                onCellRightClick = { offset ->
                    clickOffset = offset
                    menuVisible = true
                    onRightClick?.invoke(offset)
                }
            )
        }

        // Render Context Menu LOCALLY inside the Row
        if (menuVisible && contextMenuItems != null) {
            val entries = contextMenuItems(item, rowIndex)
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

// ============================================================================
// FROZEN RIGHT SECTION
// ============================================================================

@Composable
private fun <T> FrozenRightSection(
    items: ImmutableList<T>,
    columns: ImmutableList<ColumnDef<T>>,
    state: EnhancedDataGridState<T>,
    rowHeight: Dp,
    verticalScrollState: LazyListState,
    hasFocus: Boolean,
    enableEditing: Boolean,
    onCellValueChanged: ((T, String, String, String) -> Unit)?,
    selectionMode: SelectionMode,
    onRowClick: ((T, Int) -> Unit)?,
    onRowDoubleClick: ((T, Int) -> Unit)?,
    onRowRightClick: ((T, Int, Offset) -> Unit)?,
    contextMenuItems: ((T, Int) -> List<ContextMenuEntry>)?,
    rowKey: (T) -> Any,
    allColumns: ImmutableList<ColumnDef<T>>,
    allItems: ImmutableList<T>,
    frozenLeftCols: ImmutableList<ColumnDef<T>>,
    scrollableCols: ImmutableList<ColumnDef<T>>,
    onRowReorder: ((Int, Int) -> Unit)?
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val rowDragState = state.rowDragState

    CachedLazyColumn(
        state = verticalScrollState,
        modifier = Modifier.fillMaxHeight()
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> rowKey(item) }
        ) { rowIndex, item ->
            // PERFORMANCE: Wrap T in StableItem<T> to allow Compose compiler to skip recomposition
            val stableItem = StableItem(item)

            FrozenRightRow(
                itemWrapper = stableItem,
                rowIndex = rowIndex,
                columns = columns.toImmutableList(),
                state = state,
                rowHeight = rowHeight,
                hasFocus = hasFocus,
                enableEditing = enableEditing,
                onCellValueChanged = onCellValueChanged,
                selectionMode = selectionMode,
                onClick = {
                    state.selectRow(rowIndex, selectionMode)
                    onRowClick?.invoke(item, rowIndex)
                },
                onDoubleClick = {
                    onRowDoubleClick?.invoke(item, rowIndex)
                },
                onRightClick = { offset ->
                    state.selectRow(rowIndex, selectionMode)
                    onRowRightClick?.invoke(item, rowIndex, offset)
                },
                contextMenuItems = contextMenuItems,
                allColumns = allColumns,
                allItems = allItems,
                columnOffset = frozenLeftCols.size + scrollableCols.size,
                rowDragState = rowDragState,
                rowHeightPx = rowHeightPx,
                totalRows = items.size,
                onRowReorder = onRowReorder
            )

            HorizontalDivider(color = GridColors.cellBorder)
        }
    }
}

@Composable
private fun <T> FrozenRightRow(
    itemWrapper: StableItem<T>,
    rowIndex: Int,
    columns: ImmutableList<ColumnDef<T>>,
    state: EnhancedDataGridState<T>,
    rowHeight: Dp,
    hasFocus: Boolean,
    enableEditing: Boolean,
    onCellValueChanged: ((T, String, String, String) -> Unit)?,
    selectionMode: SelectionMode,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onRightClick: ((Offset) -> Unit)?,
    contextMenuItems: ((T, Int) -> List<ContextMenuEntry>)?,
    allColumns: ImmutableList<ColumnDef<T>>,
    allItems: ImmutableList<T>,
    columnOffset: Int,
    rowDragState: RowDragState? = null,
    rowHeightPx: Float = 0f,
    totalRows: Int = 0,
    onRowReorder: ((Int, Int) -> Unit)? = null
) {
    // Extract actual item from stable wrapper
    val item = itemWrapper.value

    val isSelected = rowIndex in state.selectedRows
    val isFocused = rowIndex == state.focusedRow && hasFocus
    val isDragging = rowDragState?.isDragging == true && rowDragState.draggedRowIndex == rowIndex
    val isDropTarget = rowDragState?.isDragging == true &&
            rowDragState.targetDropIndex == rowIndex &&
            rowDragState.draggedRowIndex != rowIndex

    // Local menu state for right-click handling
    var menuVisible by remember { mutableStateOf(false) }
    var clickOffset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    val backgroundColor = when {
        isDragging -> MaterialTheme.colorScheme.primaryContainer
        isDropTarget -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        isFocused && isSelected -> GridColors.rowFocused
        isSelected -> GridColors.rowSelected
        rowIndex % 2 == 1 -> GridColors.rowAlternate
        else -> Color.White
    }

    Row(
        modifier = Modifier
            .height(rowHeight)
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                if (isDragging) {
                    translationY = rowDragState.dragOffsetY
                    shadowElevation = 12f
                    scaleX = 1.02f
                    alpha = 0.95f
                }
            }
            .background(backgroundColor)
            // Use separate onRightClick modifier for reliable right-click detection
            .let { mod ->
                mod.onRightClick { offset ->
                    // Update local state for menu
                    clickOffset = offset
                    menuVisible = true

                    // Call parent handler
                    onRightClick?.invoke(offset)
                }
            }
            .draggableRowGestures(
                enabled = true,
                onLongPressStart = {
                    if (onRowReorder != null && rowDragState != null) {
                        rowDragState.startDrag(rowIndex)
                    }
                },
                onDrag = { dragDelta ->
                    if (onRowReorder != null && rowDragState != null) {
                        rowDragState.updateDrag(dragDelta.y, rowHeightPx, totalRows)
                    }
                },
                onDragEnd = {
                    if (onRowReorder != null && rowDragState != null) {
                        val result = rowDragState.endDrag()
                        if (result != null) {
                            onRowReorder(result.first, result.second)
                        }
                    }
                },
                onDragCancel = {
                    rowDragState?.cancelDrag()
                },
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onRightClick = null  // Right-click handled by separate modifier above
            )
    ) {
        columns.forEachIndexed { colIndex, column ->
            DataCell(
                itemWrapper = itemWrapper,
                rowIndex = rowIndex,
                column = column,
                columnIndex = columnOffset + colIndex,
                state = state,
                hasFocus = hasFocus,
                enableEditing = enableEditing,
                onCellValueChanged = onCellValueChanged,
                allColumns = allColumns,
                allItems = allItems,
                onCellClick = onClick,
                onCellRightClick = { offset ->
                    clickOffset = offset
                    menuVisible = true
                    onRightClick?.invoke(offset)
                }
            )
        }

        // Render Context Menu LOCALLY inside the Row
        if (menuVisible && contextMenuItems != null) {
            val entries = contextMenuItems(item, rowIndex)
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

// ============================================================================
// DATA CELL WITH EDITING
// ============================================================================

@Composable
private fun <T> DataCell(
    itemWrapper: StableItem<T>,
    rowIndex: Int,
    column: ColumnDef<T>,
    columnIndex: Int,
    state: EnhancedDataGridState<T>,
    hasFocus: Boolean,
    enableEditing: Boolean,
    onCellValueChanged: ((T, String, String, String) -> Unit)?,
    allColumns: ImmutableList<ColumnDef<T>>,
    allItems: ImmutableList<T>,
    onCellClick: (() -> Unit)? = null,
    onCellRightClick: ((Offset) -> Unit)? = null
) {
    // Extract actual item from stable wrapper
    val item = itemWrapper.value

    val width = state.getColumnWidth(column.id)
    val isSelected = rowIndex in state.selectedRows
    val isCellFocused = rowIndex == state.focusedRow && columnIndex == state.focusedColumn && hasFocus
    val isEditing = state.editingState.isEditing(rowIndex, column.id)

    val cellBg = column.cellBackground?.invoke(item) ?: Color.Transparent
    val cellTextColor = column.cellTextColor?.invoke(item) ?: Color.Unspecified
    val fontWeight = column.fontWeight?.invoke(item) ?: FontWeight.Normal
    
    val primaryColor = MaterialTheme.colorScheme.primary
    
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .drawBehind {
                // Draw cell background
                if (cellBg != Color.Transparent) {
                    drawRect(color = cellBg)
                }
                
                // Draw focus border - using drawBehind instead of Modifier.border for performance
                if (isCellFocused) {
                    val strokeWidth = 2.dp.toPx()
                    drawRect(
                        color = primaryColor,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                    )
                }
            }
            .multiButtonClickable(
                onClick = {
                    state.focusedColumn = columnIndex
                    onCellClick?.invoke()
                },
                onDoubleClick = {
                    if (enableEditing && column.editable) {
                        state.startEditing(rowIndex, column.id, allColumns, allItems)
                    }
                },
                onRightClick = onCellRightClick
            )
            .padding(horizontal = 8.dp),
        contentAlignment = when (column.alignment) {
            TextAlign.Start -> Alignment.CenterStart
            TextAlign.End -> Alignment.CenterEnd
            TextAlign.Center -> Alignment.Center
            else -> Alignment.CenterStart
        }
    ) {
        if (isEditing) {
            // Editing mode
            EditableCell(
                value = state.editingState.editingCell?.currentValue ?: "",
                onValueChange = { state.editingState.updateValue(it) },
                onCommit = {
                    val cell = state.editingState.commitEditing()
                    if (cell != null && cell.currentValue != cell.originalValue) {
                        onCellValueChanged?.invoke(item, column.id, cell.originalValue, cell.currentValue)
                    }
                },
                onCancel = { state.editingState.cancelEditing() },
                textAlign = column.alignment
            )
        } else if (column.cellContent != null) {
            // Custom cell content
            column.cellContent.invoke(item, isSelected, isEditing)
        } else {
            // Default text display
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = column.extractor(item),
                    fontSize = 13.sp,
                    color = cellTextColor,
                    fontWeight = fontWeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = column.alignment,
                    modifier = Modifier.weight(1f)
                )
                
                // Show edit icon on hover if editable
                if (enableEditing && column.editable && isCellFocused) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        modifier = Modifier.size(14.dp).padding(start = 4.dp),
                        tint = Color.Gray
                    )
                }
            }
        }
    }
}

// ============================================================================
// EDITABLE CELL INPUT
// ============================================================================

@Composable
private fun EditableCell(
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    textAlign: TextAlign
) {
    val focusRequester = remember { FocusRequester() }
    var textFieldValue by remember(value) { 
        mutableStateOf(TextFieldValue(value, TextRange(0, value.length))) 
    }
    
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = textFieldValue,
            onValueChange = { 
                textFieldValue = it
                onValueChange(it.text) 
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.Enter -> {
                                onCommit()
                                true
                            }
                            Key.Escape -> {
                                onCancel()
                                true
                            }
                            else -> false
                        }
                    } else false
                },
            textStyle = TextStyle(
                fontSize = 13.sp,
                textAlign = textAlign
            ),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    innerTextField()
                }
            }
        )
        
        // Commit button
        IconButton(
            onClick = onCommit,
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Save",
                tint = GridColors.positiveValue,
                modifier = Modifier.size(14.dp)
            )
        }
        
        // Cancel button
        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel",
                tint = GridColors.negativeValue,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// ============================================================================
// STICKY FOOTER ROW
// ============================================================================

@Composable
private fun <T> StickyFooterRow(
    totals: Map<String, String>,
    columns: ImmutableList<ColumnDef<T>>,
    state: EnhancedDataGridState<T>,
    showRowNumbers: Boolean,
    rowNumberWidth: Dp,
    rowHeight: Dp,
    horizontalScrollState: ScrollState,
    frozenLeftWidth: Dp,
    frozenRightWidth: Dp,
    frozenLeftCols: ImmutableList<ColumnDef<T>>,
    frozenRightCols: ImmutableList<ColumnDef<T>>,
    scrollableCols: ImmutableList<ColumnDef<T>>,
    stickyColumns: StickyColumnsConfig
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(GridColors.headerBackground)
    ) {
        // Frozen left footer
        if (frozenLeftWidth > 0.dp) {
            Row(modifier = Modifier.width(frozenLeftWidth)) {
                if (showRowNumbers && stickyColumns.freezeRowNumbers) {
                    Box(
                        modifier = Modifier
                            .width(rowNumberWidth)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Σ",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
                
                frozenLeftCols.forEach { column ->
                    TotalsCell(
                        value = totals[column.id] ?: "",
                        width = state.getColumnWidth(column.id),
                        alignment = column.alignment
                    )
                }
            }
            
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(GridColors.headerBorder)
            )
        }
        
        // Scrollable footer
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(horizontalScrollState)
        ) {
            if (showRowNumbers && !stickyColumns.freezeRowNumbers) {
                Box(
                    modifier = Modifier
                        .width(rowNumberWidth)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Σ",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
            
            scrollableCols.forEach { column ->
                TotalsCell(
                    value = totals[column.id] ?: "",
                    width = state.getColumnWidth(column.id),
                    alignment = column.alignment
                )
            }
        }
        
        // Frozen right footer
        if (frozenRightWidth > 0.dp) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(GridColors.headerBorder)
            )
            
            Row(modifier = Modifier.width(frozenRightWidth)) {
                frozenRightCols.forEach { column ->
                    TotalsCell(
                        value = totals[column.id] ?: "",
                        width = state.getColumnWidth(column.id),
                        alignment = column.alignment
                    )
                }
            }
        }
    }
}

@Composable
private fun TotalsCell(
    value: String,
    width: Dp,
    alignment: TextAlign
) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 8.dp),
        contentAlignment = when (alignment) {
            TextAlign.Start -> Alignment.CenterStart
            TextAlign.End -> Alignment.CenterEnd
            TextAlign.Center -> Alignment.Center
            else -> Alignment.CenterStart
        }
    ) {
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
