package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.UIConst

/**
 * A scrolling console of log lines, with a VISIBLE scrollbar.
 *
 * The scrollbar is the feature, not decoration. A bounded, auto-scrolling pane
 * that shows the tail of a long run looks exactly like a pane that is showing
 * everything there is - the operator has no way to tell that hundreds of lines
 * scrolled past, and no reason to think of dragging. The bar's thumb size is the
 * only honest signal of how much is hidden.
 *
 * [autoScroll] follows the tail as lines arrive, but ONLY while the operator is
 * already at the bottom: yanking the view back down while they are reading
 * something further up is how a live console becomes unusable.
 */
@Composable
fun AppLogConsole(
    lines: List<String>,
    modifier: Modifier = Modifier,
    minHeight: Dp = 120.dp,
    maxHeight: Dp = 320.dp,
    autoScroll: Boolean = true,
) {
    val listState = rememberLazyListState()

    // "At the bottom" is decided BEFORE the new line lands, so a line arriving
    // while the operator reads history does not count as them being at the tail.
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf true
            last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }

    LaunchedEffect(lines.size) {
        if (autoScroll && atBottom && lines.isNotEmpty()) {
            listState.scrollToItem(lines.lastIndex)
        }
    }

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight, max = maxHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().padding(UIConst.paddingSmall),
            ) {
                items(lines) { line ->
                    AppText(line, AppTextStyle.LOG_LINE)
                }
            }
        }
        // No width: the platform scrollbar has its own intrinsic one (the
        // scheduler grid relies on exactly this), so naming a number here would
        // just be a magic one that drifts from the platform's.
        AppVerticalScrollbar(
            lazyListState = listState,
            modifier = Modifier.fillMaxHeight(),
        )
    }
}
