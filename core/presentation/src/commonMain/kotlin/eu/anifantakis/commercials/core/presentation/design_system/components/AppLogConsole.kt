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
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

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

private val sampleRunLog = listOf(
    "10:02:01  Connected to Crete TV traffic desk",
    "10:02:01  Loading contracts for Wednesday 15 July",
    "10:02:02  Aegean Foods       CTV-2026-014   120 spots",
    "10:02:02  Minoan Travel      CTV-2026-021    48 spots",
    "10:02:02  Heraklion Motors   CTV-2025-198    12 spots",
    "10:02:03  Building the break scaffold (06:00 - 23:59)",
    "10:02:04  14 breaks, 38 spots placed",
    "10:02:05  WARN  break 21:00 is at 174s of 180s",
    "10:02:06  Rendering the timetable",
    "10:02:07  Mirroring the run to Radio 984",
    "10:02:09  Radio 984: 9 breaks, 21 spots placed",
    "10:02:10  Done in 8.4s",
)

// A long run: more lines than fit, so the scrollbar thumb is the only honest
// signal of how much is hidden - that is the component's whole reason to exist.
@Preview
@Composable
private fun AppLogConsolePreview() = AppPreview {
    AppLogConsole(lines = sampleRunLog)
}

// Nothing has run yet: the console still holds its minHeight instead of
// collapsing the layout out from under whatever sits below it.
@Preview
@Composable
private fun AppLogConsoleEmptyPreview() = AppPreview {
    AppLogConsole(lines = emptyList())
}

// A short run - fewer lines than the pane: no scrollbar thumb to drag, and the
// pane must NOT pretend there is more to see.
@Preview
@Composable
private fun AppLogConsoleShortPreview() = AppPreview {
    AppLogConsole(lines = sampleRunLog.take(3))
}
