package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun AppVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier
) {
    // Safety check: only show scrollbar if content exceeds viewport
    if (scrollState.maxValue > 0) {
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = modifier
        )
    }
}

@Composable
actual fun AppVerticalScrollbar(
    lazyListState: LazyListState,
    modifier: Modifier
) {
    // Safety check: only show scrollbar if list has items and layout is ready
    val layoutInfo = lazyListState.layoutInfo
    if (layoutInfo.totalItemsCount > 0 && layoutInfo.visibleItemsInfo.isNotEmpty()) {
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(lazyListState),
            modifier = modifier
        )
    }
}

@Composable
actual fun AppHorizontalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier
) {
    // Safety check: only show scrollbar if content exceeds viewport
    if (scrollState.maxValue > 0) {
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = modifier
        )
    }
}
