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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Configuration for the cache window (prefetch buffer) used by lazy layouts.
 * Defines how much content to keep composed ahead of and behind the visible area.
 */
@Stable
sealed interface CacheWindowConfig {
    /**
     * Fixed Dp-based cache window configuration.
     *
     * @param ahead The amount of content (in [Dp]) to prefetch ahead of the visible area.
     * @param behind The amount of content (in [Dp]) to keep composed behind the visible area.
     */
    data class Fixed(val ahead: Dp, val behind: Dp) : CacheWindowConfig {
        /** Creates a symmetric cache window with the same size ahead and behind. */
        constructor(both: Dp) : this(ahead = both, behind = both)
    }

    /**
     * Fraction-based cache window configuration that scales with viewport size.
     * More adaptive across different screen sizes.
     *
     * @param ahead Fraction of viewport to prefetch ahead (e.g., 0.5f = half viewport).
     * @param behind Fraction of viewport to keep composed behind (e.g., 0.3f = 30% of viewport).
     */
    data class Fraction(val ahead: Float, val behind: Float) : CacheWindowConfig {
        /** Creates a symmetric cache window with the same fraction ahead and behind. */
        constructor(both: Float) : this(ahead = both, behind = both)
    }

    companion object {
        /** Default configuration for lists: 50% viewport ahead, 30% behind */
        val DefaultList = Fraction(ahead = 0.5f, behind = 0.3f)

        /** Default configuration for grids: 40% viewport ahead, 25% behind (more conservative due to item density) */
        val DefaultGrid = Fraction(ahead = 0.4f, behind = 0.25f)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun CacheWindowConfig.toLazyLayoutCacheWindow(): LazyLayoutCacheWindow = when (this) {
    is CacheWindowConfig.Fixed -> LazyLayoutCacheWindow(ahead = ahead, behind = behind)
    is CacheWindowConfig.Fraction -> LazyLayoutCacheWindow(aheadFraction = ahead, behindFraction = behind)
}

// ============================================================================
// LIST STATE
// ============================================================================

/**
 * Creates a [LazyListState] that is remembered across compositions and includes a pre-configured
 * cache window (prefetch buffer) using [LazyLayoutCacheWindow] to enhance scrolling performance.
 *
 * @param initialFirstVisibleItemIndex the initial value for [LazyListState.firstVisibleItemIndex].
 * @param initialFirstVisibleItemScrollOffset the initial value for [LazyListState.firstVisibleItemScrollOffset].
 * @param cacheConfig Configuration for the prefetch cache window. Defaults to [CacheWindowConfig.DefaultList].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberCachedLazyListState(
    cacheConfig: CacheWindowConfig = CacheWindowConfig.DefaultList,
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyListState {
    val cacheWindow = remember(cacheConfig) {
        cacheConfig.toLazyLayoutCacheWindow()
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
 * @param contentPadding a padding around the whole content.
 * @param reverseLayout reverse the direction of scrolling and layout.
 * @param verticalArrangement The vertical arrangement of the layout's children.
 * @param horizontalAlignment the horizontal alignment applied to the items.
 * @param flingBehavior logic describing fling behavior.
 * @param userScrollEnabled whether the scrolling via the user gestures or accessibility actions is allowed.
 * @param content a block which describes the content.
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
 * @param contentPadding a padding around the whole content.
 * @param reverseLayout reverse the direction of scrolling and layout.
 * @param horizontalArrangement The horizontal arrangement of the layout's children.
 * @param verticalAlignment the vertical alignment applied to the items.
 * @param flingBehavior logic describing fling behavior.
 * @param userScrollEnabled whether the scrolling via the user gestures or accessibility actions is allowed.
 * @param content a block which describes the content.
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
// GRID STATE
// ============================================================================

/**
 * Creates a [LazyGridState] that is remembered across compositions and includes a pre-configured
 * cache window (prefetch buffer) using [LazyLayoutCacheWindow] to enhance scrolling performance.
 *
 * @param initialFirstVisibleItemIndex the initial value for [LazyGridState.firstVisibleItemIndex].
 * @param initialFirstVisibleItemScrollOffset the initial value for [LazyGridState.firstVisibleItemScrollOffset].
 * @param cacheConfig Configuration for the prefetch cache window. Defaults to [CacheWindowConfig.DefaultGrid].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberCachedLazyGridState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
    cacheConfig: CacheWindowConfig = CacheWindowConfig.DefaultGrid,
): LazyGridState {
    val cacheWindow = remember(cacheConfig) {
        cacheConfig.toLazyLayoutCacheWindow()
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