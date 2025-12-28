package eu.anifantakis.poc.ctv.grids

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Android uses touch scrolling, so no visible scrollbars needed.
 */

@Composable
actual fun PlatformVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier
) {
    // No-op on Android - touch scrolling doesn't need visible scrollbars
}

@Composable
actual fun PlatformVerticalScrollbar(
    lazyListState: LazyListState,
    modifier: Modifier
) {
    // No-op on Android - touch scrolling doesn't need visible scrollbars
}

@Composable
actual fun PlatformHorizontalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier
) {
    // No-op on Android - touch scrolling doesn't need visible scrollbars
}
