package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one width/height bucket the app needs - deliberately NOT
 * material3-adaptive (an extra artifact to keep in lockstep across five
 * target families, for 25 lines of BoxWithConstraints).
 *
 * Three jobs, all outside the grids: dialogs go full-bleed on COMPACT,
 * AppFormColumn caps its width elsewhere, and chrome density adapts.
 * The grids never read it - "same grid everywhere, touch-tuned" means
 * geometry tuned by injected metrics, never re-laid-out by window class.
 */
enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }

enum class WindowHeightClass { COMPACT, MEDIUM, EXPANDED }

@Immutable
data class WindowSize(
    val width: WindowWidthClass,
    val height: WindowHeightClass,
    val widthDp: Dp,
    val heightDp: Dp,
) {
    val isCompact: Boolean get() = width == WindowWidthClass.COMPACT
}

/**
 * Regular (not static) local - it CHANGES on every window resize, and only
 * readers should recompose. Safe default so standalone previews render.
 */
val LocalWindowSize = compositionLocalOf {
    WindowSize(WindowWidthClass.EXPANDED, WindowHeightClass.EXPANDED, 1280.dp, 800.dp)
}

/**
 * Wrap INSIDE the safe-content padding so the measured size excludes
 * system bars (App.kt places it inside the root Surface).
 */
@Composable
fun WindowSizeProvider(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = WindowSize(
            width = when {
                maxWidth < 600.dp -> WindowWidthClass.COMPACT
                maxWidth < 1024.dp -> WindowWidthClass.MEDIUM
                else -> WindowWidthClass.EXPANDED
            },
            height = when {
                maxHeight < 480.dp -> WindowHeightClass.COMPACT
                maxHeight < 900.dp -> WindowHeightClass.MEDIUM
                else -> WindowHeightClass.EXPANDED
            },
            widthDp = maxWidth,
            heightDp = maxHeight,
        )
        CompositionLocalProvider(LocalWindowSize provides size, content = content)
    }
}
