package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.mediumSpec
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings

/** The drag-resize floor; below this a panel header stops being usable. */
val APP_PANEL_MIN_WIDTH = 360.dp

/** How much of the app must stay visible (and clickable) beside a panel. */
val APP_PANEL_MIN_UNCOVERED = 240.dp

/**
 * A companion panel that slides OVER the content from the end edge (the
 * start edge under RTL, for free via [Alignment.CenterEnd]) instead of
 * squeezing it: the app never reflows, and everything the panel does not
 * cover keeps taking clicks.
 *
 * Drag-resizable from its inner edge between [minWidth] and whatever leaves
 * [minUncovered] of app visible; the committed width goes back through
 * [onWidthCommitted] so the caller decides where it persists. Collapsing
 * parks the panel into a slim mid-edge tab - one click re-expands it to
 * exactly the width it had.
 *
 * Host it as a TOP layer over the app's content box, not inside a screen.
 * Two panels on the same edge would overlap, so a host that has more than
 * one is expected to keep at most one visible.
 *
 * @param windowWidth the width the panel may eat into - caps the drag.
 * @param content receives the sizing [Modifier] it must apply, and the
 *   callback that parks it; render a collapse affordance only if you want one.
 */
@Composable
fun AppSlideOverPanel(
    visible: Boolean,
    windowWidth: Dp,
    initialWidth: Dp,
    onWidthCommitted: (Dp) -> Unit,
    minWidth: Dp = APP_PANEL_MIN_WIDTH,
    minUncovered: Dp = APP_PANEL_MIN_UNCOVERED,
    content: @Composable (modifier: Modifier, onCollapse: () -> Unit) -> Unit,
) {
    if (!visible) return
    var preferredWidth by remember { mutableStateOf(initialWidth) }
    var collapsed by remember { mutableStateOf(false) }
    val ceiling = (windowWidth - minUncovered).coerceAtLeast(minWidth)
    val panelWidth = preferredWidth.coerceIn(minWidth, ceiling)
    val density = LocalDensity.current
    val motion = AppTheme.visualTokens.motion
    val a11y = AppTheme.a11y
    // Slide offsets are raw x pixels - mirror them by hand under RTL, where
    // the END edge (and therefore the panel) is on the LEFT.
    val edge = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    // Dragging the handle AWAY from the panel grows it.
    val grow = -edge

    Box(Modifier.fillMaxSize()) {
        // The parked state: a slim tab on the edge, one click to re-expand.
        AnimatedVisibility(
            visible = collapsed,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(motion.mediumSpec(a11y)),
            exit = fadeOut(motion.mediumSpec(a11y)),
        ) {
            Surface(
                shadowElevation = 8.dp,
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                modifier = Modifier
                    .width(28.dp)
                    .height(88.dp)
                    .clickable { collapsed = false },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(
                        AppDrawableRepo.keyboardArrowLeft,
                        contentDescription = Strings[StringKey.COMMON_EXPAND],
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !collapsed,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            enter = slideInHorizontally(motion.mediumSpec(a11y)) { it * edge },
            exit = slideOutHorizontally(motion.mediumSpec(a11y)) { it * edge },
        ) {
            Row(Modifier.fillMaxHeight()) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(8.dp)
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                val d = with(density) { delta.toDp() }
                                preferredWidth = (panelWidth + d * grow).coerceIn(minWidth, ceiling)
                            },
                            onDragStopped = { onWidthCommitted(preferredWidth) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    VerticalDivider()
                }
                Surface(shadowElevation = 12.dp, tonalElevation = 2.dp) {
                    content(Modifier.width(panelWidth).fillMaxHeight()) { collapsed = true }
                }
            }
        }
    }
}
