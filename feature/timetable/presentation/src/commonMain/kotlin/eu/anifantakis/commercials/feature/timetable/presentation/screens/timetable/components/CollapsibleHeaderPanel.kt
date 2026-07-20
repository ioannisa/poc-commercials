package eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconSize
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings

/**
 * A top panel with ONE gesture: click the divider to collapse it away (grid
 * full-screen) and click again to bring it back. No drag, no in-between
 * heights - the operator wanted a switch, not a slider.
 *
 * Expanded shows [header] at its natural height, capped so the grid keeps at
 * least [minBodyHeight]; anything the cap hides scrolls inside the panel, so
 * nothing is unreachable even on a short window. Collapsed hides the header
 * entirely. Collapse is a session state - it resets on reload.
 *
 * Geometry only - it knows nothing of the timetable. [body] (the grid) takes
 * whatever the panel and the divider leave.
 */
@Composable
internal fun CollapsibleHeaderPanel(
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** The grid always keeps at least this, so a tall header can't hide it. */
    minBodyHeight: Dp = 160.dp,
) {
    val density = LocalDensity.current
    var collapsed by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier) {
        val availablePx = constraints.maxHeight
        val bodyFloorPx = with(density) { minBodyHeight.roundToPx() }
        // The header's natural (unscrolled) height.
        var contentPx by remember { mutableStateOf(0) }

        val maxPx = (availablePx - bodyFloorPx).coerceAtLeast(0)
        val expandedPx = contentPx.coerceAtMost(maxPx)
        val displayPx = if (collapsed) 0 else expandedPx
        val panelHeight = with(density) { displayPx.toDp() }

        Column(Modifier.fillMaxWidth()) {
            // ── the panel: natural header height (capped), scrolls if capped ──
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(panelHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(Modifier.onSizeChanged { contentPx = it.height }) {
                    header()
                }
            }

            // ── the divider: one click collapses, one click restores ──
            Surface(
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { collapsed = !collapsed },
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The chevron points where the click will move the panel:
                    // up to tuck it away, down to bring it back.
                    AppIcon(
                        if (collapsed) AppDrawableRepo.keyboardArrowDown else AppDrawableRepo.keyboardArrowUp,
                        contentDescription = Strings[if (collapsed) StringKey.COMMON_EXPAND else StringKey.COMMON_COLLAPSE],
                        size = AppIconSize.SMALL,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── the body (grid): everything the panel and divider leave ──
            Box(Modifier.fillMaxWidth().weight(1f)) {
                body()
            }
        }
    }
}
