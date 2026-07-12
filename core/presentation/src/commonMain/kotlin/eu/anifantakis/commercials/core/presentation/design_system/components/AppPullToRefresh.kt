package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Pull-to-refresh wrapper around a scrollable list (AndroidSkeletonApp
 * heritage; M3 PullToRefreshBox is multiplatform). [isRefreshing] is separate
 * state from the global loading overlay ON PURPOSE: a pull refresh shows the
 * inline indicator and keeps the list interactive, it never raises the
 * blocking AppLoadingIndicator.
 *
 * Touch-platform gesture: on desktop/web there is no pull gesture — always
 * offer the same [onRefresh] through a visible affordance too (toolbar
 * refresh button / keyboard shortcut).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()

    PullToRefreshBox(
        state = state,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
    ) {
        content()
    }
}
