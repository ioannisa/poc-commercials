package eu.anifantakis.poc.ctv.grids

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Creates a [LazyListState] that is remembered across compositions and includes a pre-configured 
 * cache window (prefetch buffer) using [LazyLayoutCacheWindow] to enhance scrolling performance.
 *
 * @param initialFirstVisibleItemIndex the initial value for [LazyListState.firstVisibleItemIndex].
 * @param initialFirstVisibleItemScrollOffset the initial value for [LazyListState.firstVisibleItemScrollOffset].
 * @param ahead The amount of content (in [Dp]) to prefetch and keep composed ahead of the visible area 
 * in the scrolling direction. This helps eliminate jank when new items enter the viewport.
 * @param behind The amount of content (in [Dp]) to keep composed behind the current scroll position. 
 * This prevents immediate disposal of items that just left the screen, making reverse scrolling smoother.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberCachedLazyListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
    ahead: Dp = 500.dp,
    behind: Dp = 500.dp,
): LazyListState {
    val cacheWindow = remember(ahead, behind) {
        LazyLayoutCacheWindow(ahead = ahead, behind = behind)
    }
    
    return rememberLazyListState(
        cacheWindow = cacheWindow,
        initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset
    )
}

/**
 * A vertically scrolling list that only composes and lays out the currently visible items, 
 * with an added [LazyLayoutCacheWindow] to pre-compose off-screen content for smoother performance.
 *
 * @param modifier the modifier to apply to this layout.
 * @param state the state object to be used to control or observe the list's state. 
 * Defaults to a state created via [rememberCachedLazyListState].
 * @param contentPadding a padding around the whole content. This will add padding for the 
 * content after it has been clipped, which is not possible via [modifier] param. You can use it 
 * to add a padding before the first item or after the last one.
 * @param reverseLayout reverse the direction of scrolling and layout. When `true`, items will be 
 * composed from the bottom to the top and [LazyListState.firstVisibleItemIndex] == 0 will mean 
 * we scrolled to the bottom.
 * @param verticalArrangement The vertical arrangement of the layout's children. This allows 
 * to add a spacing between items and specify the arrangement of the items when we have not 
 * enough of them to fill the whole minimum size.
 * @param horizontalAlignment the horizontal alignment applied to the items.
 * @param flingBehavior logic describing fling behavior.
 * @param userScrollEnabled whether the scrolling via the user gestures or accessibility actions 
 * is allowed. You can still scroll programmatically via [state] even when it is disabled.
 * @param content a block which describes the content. Inside this block you can use methods 
 * like [LazyListScope.item] to add a single item or [LazyListScope.items] to add a list of items.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CachedLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberCachedLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = content
    )
}

/**
 * A horizontally scrolling list that only composes and lays out the currently visible items, 
 * with an added [LazyLayoutCacheWindow] to pre-compose off-screen content for smoother performance.
 *
 * @param modifier the modifier to apply to this layout.
 * @param state the state object to be used to control or observe the list's state. 
 * Defaults to a state created via [rememberCachedLazyListState].
 * @param contentPadding a padding around the whole content. This will add padding for the 
 * content after it has been clipped, which is not possible via [modifier] param. You can use it 
 * to add a padding before the first item or after the last one.
 * @param reverseLayout reverse the direction of scrolling and layout. When `true`, items will be 
 * composed from the end to the start and [LazyListState.firstVisibleItemIndex] == 0 will mean 
 * we scrolled to the end.
 * @param horizontalArrangement The horizontal arrangement of the layout's children. This allows 
 * to add a spacing between items and specify the arrangement of the items when we have not 
 * enough of them to fill the whole minimum size.
 * @param verticalAlignment the vertical alignment applied to the items.
 * @param flingBehavior logic describing fling behavior.
 * @param userScrollEnabled whether the scrolling via the user gestures or accessibility actions 
 * is allowed. You can still scroll programmatically via [state] even when it is disabled.
 * @param content a block which describes the content. Inside this block you can use methods 
 * like [LazyListScope.item] to add a single item or [LazyListScope.items] to add a list of items.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CachedLazyRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberCachedLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    horizontalArrangement: Arrangement.Horizontal =
        if (!reverseLayout) Arrangement.Start else Arrangement.End,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit
) {
    LazyRow(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = content
    )
}

// ============================================================================
// GRID VARIANTS
// ============================================================================

/**
 * Creates a [LazyGridState] that is remembered across compositions and includes a pre-configured 
 * cache window (prefetch buffer) using [LazyLayoutCacheWindow] to enhance scrolling performance.
 *
 * @param initialFirstVisibleItemIndex the initial value for [LazyGridState.firstVisibleItemIndex].
 * @param initialFirstVisibleItemScrollOffset the initial value for [LazyGridState.firstVisibleItemScrollOffset].
 * @param ahead The amount of content (in [Dp]) to prefetch and keep composed ahead of the visible area 
 * in the scrolling direction. This helps eliminate jank when new items enter the viewport.
 * @param behind The amount of content (in [Dp]) to keep composed behind the current scroll position. 
 * This prevents immediate disposal of items that just left the screen, making reverse scrolling smoother.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberCachedLazyGridState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
    ahead: Dp = 500.dp,
    behind: Dp = 500.dp,
): LazyGridState {
    val cacheWindow = remember(ahead, behind) {
        LazyLayoutCacheWindow(ahead = ahead, behind = behind)
    }
    
    return rememberLazyGridState(
        cacheWindow = cacheWindow,
        initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset
    )
}

/**
 * A vertical grid that only composes and lays out the currently visible items, 
 * with an added [LazyLayoutCacheWindow] to pre-compose off-screen content for smoother performance.
 *
 * @param columns describes the count and the size of the grid's columns.
 * @param modifier the modifier to apply to this layout.
 * @param state the state object to be used to control or observe the list's state. 
 * Defaults to a state created via [rememberCachedLazyGridState].
 * @param contentPadding a padding around the whole content.
 * @param reverseLayout reverse the direction of scrolling and layout.
 * @param verticalArrangement The vertical arrangement of the layout's children.
 * @param horizontalArrangement The horizontal arrangement of the layout's children.
 * @param flingBehavior logic describing fling behavior.
 * @param userScrollEnabled whether the scrolling via the user gestures or accessibility actions is allowed.
 * @param content a block which describes the content.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CachedLazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberCachedLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyGridScope.() -> Unit
) {
    LazyVerticalGrid(
        columns = columns,
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalArrangement = horizontalArrangement,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = content
    )
}

/**
 * A horizontal grid that only composes and lays out the currently visible items, 
 * with an added [LazyLayoutCacheWindow] to pre-compose off-screen content for smoother performance.
 *
 * @param rows describes the count and the size of the grid's rows.
 * @param modifier the modifier to apply to this layout.
 * @param state the state object to be used to control or observe the list's state. 
 * Defaults to a state created via [rememberCachedLazyGridState].
 * @param contentPadding a padding around the whole content.
 * @param reverseLayout reverse the direction of scrolling and layout.
 * @param verticalArrangement The vertical arrangement of the layout's children.
 * @param horizontalArrangement The horizontal arrangement of the layout's children.
 * @param flingBehavior logic describing fling behavior.
 * @param userScrollEnabled whether the scrolling via the user gestures or accessibility actions is allowed.
 * @param content a block which describes the content.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CachedLazyHorizontalGrid(
    rows: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberCachedLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal =
        if (!reverseLayout) Arrangement.Start else Arrangement.End,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyGridScope.() -> Unit
) {
    LazyHorizontalGrid(
        rows = rows,
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalArrangement = horizontalArrangement,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = content
    )
}
