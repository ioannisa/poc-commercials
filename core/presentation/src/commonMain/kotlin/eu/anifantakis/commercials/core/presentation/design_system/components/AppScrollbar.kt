package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

/**
 * Scrollbars, per platform. Desktop and web get a REAL one (draggable, and -
 * the point - it makes hidden content visible); touch platforms get nothing,
 * because a scrollbar there is chrome for a gesture users already know.
 *
 * It lives in the DESIGN SYSTEM, not in :grids where it started: a scrollbar is
 * a platform UI capability, not a property of the scheduler grid, and the second
 * screen that needed one (the migration console) could not reach it there.
 * Desktop/Web platforms show actual scrollbars.
 * Mobile platforms (iOS/Android) don't show scrollbars as they use touch scrolling.
 */

@Composable
expect fun AppVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier
)

@Composable
expect fun AppVerticalScrollbar(
    lazyListState: LazyListState,
    modifier: Modifier
)

@Composable
expect fun AppHorizontalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier
)

/**
 * NOTE for whoever reads these on a touch target: on Android/iOS the actual
 * draws NOTHING, so the preview shows the list at full width with no bar beside
 * it. That is the component working, not the preview failing.
 */

// The LAZY overload: a long spot list that overflows its pane. The thumb's size
// is the only thing telling the operator that 26 more rows exist below.
@Preview
@Composable
private fun AppVerticalScrollbarLazyPreview() = AppPreview {
    val spots = remember {
        List(30) { index -> "Spot ${index + 1} - Aegean Foods - 30s - Crete TV" }
    }
    val listState = rememberLazyListState()
    Row(Modifier.height(180.dp)) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(spots) { spot ->
                AppText(spot, AppTextStyle.TABLE_CELL, modifier = Modifier.padding(UIConst.paddingExtraSmall))
            }
        }
        AppVerticalScrollbar(lazyListState = listState, modifier = Modifier.fillMaxHeight())
    }
}

// The SHORT list: everything fits, so there is nothing hidden and (on desktop/
// web) the bar has no drag to offer. A scrollbar that still showed a full-height
// thumb here would be lying in the other direction.
@Preview
@Composable
private fun AppVerticalScrollbarLazyShortPreview() = AppPreview {
    val breaks = remember { listOf("06:00 break", "08:30 break", "13:00 break") }
    val listState = rememberLazyListState()
    Row(Modifier.height(180.dp)) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(breaks) { item ->
                AppText(item, AppTextStyle.TABLE_CELL, modifier = Modifier.padding(UIConst.paddingExtraSmall))
            }
        }
        AppVerticalScrollbar(lazyListState = listState, modifier = Modifier.fillMaxHeight())
    }
}

// The ScrollState overload: a plain verticalScroll column (a settings pane, a
// contract's terms) rather than a lazy list.
@Preview
@Composable
private fun AppVerticalScrollbarScrollStatePreview() = AppPreview {
    val scrollState = rememberScrollState()
    Row(Modifier.height(180.dp)) {
        Column(Modifier.weight(1f).verticalScroll(scrollState)) {
            repeat(24) { index ->
                AppText(
                    "Break at ${6 + index}:00 - Radio 984",
                    AppTextStyle.TABLE_CELL,
                    modifier = Modifier.padding(UIConst.paddingExtraSmall),
                )
            }
        }
        AppVerticalScrollbar(scrollState = scrollState, modifier = Modifier.fillMaxHeight())
    }
}

// Horizontal: the scheduler grid's day columns run off the right edge, and the
// bar under them is the only sign that more stations exist over there.
@Preview
@Composable
private fun AppHorizontalScrollbarPreview() = AppPreview {
    val scrollState = rememberScrollState()
    Column(Modifier.width(260.dp)) {
        Row(Modifier.horizontalScroll(scrollState)) {
            repeat(12) { index ->
                AppText(
                    "Crete TV / break ${8 + index}:00",
                    AppTextStyle.TABLE_CELL,
                    modifier = Modifier.padding(UIConst.paddingSmall),
                )
            }
        }
        AppHorizontalScrollbar(scrollState = scrollState, modifier = Modifier.fillMaxWidth())
    }
}
