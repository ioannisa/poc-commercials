package eu.anifantakis.poc.ctv.grids

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-specific scrollbar composables.
 * Desktop/Web platforms show actual scrollbars.
 * Mobile platforms (iOS/Android) don't show scrollbars as they use touch scrolling.
 */

@Composable
expect fun PlatformVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier
)

@Composable
expect fun PlatformVerticalScrollbar(
    lazyListState: LazyListState,
    modifier: Modifier
)

@Composable
expect fun PlatformHorizontalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier
)
