package eu.anifantakis.commercials.grids

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Input-driven grid tuning, INJECTED by the app shell (this module is a leaf
 * toolkit - it never reads the app's design system; same contract as
 * SchedulerLabels/GridMetrics-style doors). Defaults are today's pointer
 * behaviour, so a caller that provides nothing changes nothing.
 *
 * Per-gesture behaviour (long-press menus, hover) keys off each EVENT's
 * PointerType inside GridCore - this config only carries the static
 * geometry choices a session makes up front.
 */
@Immutable
data class GridInputConfig(
    /**
     * Floor for the grid scale: coarse-pointer sessions raise it so cells
     * stay honestly tappable (~1.3 ->  55x36dp scheduler cells).
     */
    val minScale: Float = 1f,
    /** Extra INVISIBLE hit width around resize/drag handles. */
    val handleSlop: Dp = 0.dp,
)

val LocalGridInput = staticCompositionLocalOf { GridInputConfig() }
