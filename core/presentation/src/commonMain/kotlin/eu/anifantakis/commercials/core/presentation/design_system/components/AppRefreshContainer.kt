package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

/**
 * The adaptive refresh container (successor of the never-adopted
 * AppPullToRefresh). Refresh is an INTERACTION capability, not a visual
 * token: touch sessions get the pull gesture; pointer sessions pass the
 * content through untouched and reach the SAME [onRefresh] via a visible
 * affordance (toolbar Refresh / F5 - wired with the desktop command
 * registry). On a hybrid device both coexist.
 *
 * [isRefreshing] is separate from the global loading overlay ON PURPOSE:
 * a refresh shows the inline indicator and keeps the list interactive; it
 * never raises the blocking AppLoadingIndicator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!AppTheme.interaction.pullToRefresh) {
        Box(modifier) { content() }
        return
    }
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

/** A station list - the kind of content that is actually pulled to refresh. */
@Composable
private fun RefreshableStationList() {
    val stations = listOf(
        "Crete TV - 14 breaks today",
        "Radio 984 - 9 breaks today",
        "Minoan FM - 6 breaks today",
        "Heraklion Radio - 4 breaks today",
        "Chania TV - no breaks scheduled",
    )
    LazyColumn(Modifier.fillMaxWidth()) {
        items(stations) { station ->
            AppText(
                station,
                AppTextStyle.BODY,
                modifier = Modifier.padding(UIConst.paddingSmall),
            )
        }
    }
}

// Idle. On a POINTER session this is all there is: the content passes through
// untouched and the same onRefresh is reached from the toolbar / F5 instead.
@Preview
@Composable
private fun AppRefreshContainerPreview() = AppPreview {
    AppRefreshContainer(
        isRefreshing = false,
        onRefresh = {},
        modifier = Modifier.height(200.dp),
    ) {
        RefreshableStationList()
    }
}

// Refreshing: the INLINE indicator shows and the list stays interactive - this
// must never raise the blocking AppLoadingIndicator.
@Preview
@Composable
private fun AppRefreshContainerRefreshingPreview() = AppPreview {
    AppRefreshContainer(
        isRefreshing = true,
        onRefresh = {},
        modifier = Modifier.height(200.dp),
    ) {
        RefreshableStationList()
    }
}
