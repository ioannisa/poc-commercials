package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * iOS uses touch scrolling, so no visible scrollbars needed.
 */

@Composable
actual fun AppVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier
) {
    // No-op on iOS - touch scrolling doesn't need visible scrollbars
}

@Composable
actual fun AppVerticalScrollbar(
    lazyListState: LazyListState,
    modifier: Modifier
) {
    // No-op on iOS - touch scrolling doesn't need visible scrollbars
}

@Composable
actual fun AppHorizontalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier
) {
    // No-op on iOS - touch scrolling doesn't need visible scrollbars
}
