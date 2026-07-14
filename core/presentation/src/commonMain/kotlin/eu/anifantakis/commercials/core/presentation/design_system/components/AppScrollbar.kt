package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
