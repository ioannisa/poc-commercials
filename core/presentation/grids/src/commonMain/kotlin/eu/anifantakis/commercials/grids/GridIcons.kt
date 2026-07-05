package eu.anifantakis.commercials.core.presentation.grids

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The grid toolkit's own "single door" for icons - the same house rule as the
 * app's `AppIcons`, but kept LOCAL so this module stays a self-contained,
 * app-agnostic pure-UI toolkit (it depends on nothing internal, not even
 * :core:presentation). Every icon the grid renders is named once here; the
 * grid composables reference `GridIcons.*` instead of raw `Icons.Default.*`.
 * Mirrors the Material leaf name 1:1; alongside [GridTheme], this is the grid's
 * complete design-system surface.
 */
object GridIcons {
    val keyboardArrowLeft: ImageVector @Composable get() = Icons.AutoMirrored.Filled.KeyboardArrowLeft
    val keyboardArrowRight: ImageVector @Composable get() = Icons.AutoMirrored.Filled.KeyboardArrowRight
    val arrowDownward: ImageVector @Composable get() = Icons.Default.ArrowDownward
    val arrowUpward: ImageVector @Composable get() = Icons.Default.ArrowUpward
    val check: ImageVector @Composable get() = Icons.Default.Check
    val close: ImageVector @Composable get() = Icons.Default.Close
    val edit: ImageVector @Composable get() = Icons.Default.Edit
}
