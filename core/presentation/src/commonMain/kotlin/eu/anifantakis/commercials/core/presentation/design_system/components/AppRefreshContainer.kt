package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme

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
