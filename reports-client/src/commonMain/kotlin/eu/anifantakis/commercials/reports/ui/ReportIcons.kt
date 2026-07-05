package eu.anifantakis.commercials.reports.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The report client's own "single door" for icons - the same house rule as the
 * app's `AppIcons`, kept LOCAL so this cross-layer report module stays
 * self-contained and does NOT take a dependency on :core:presentation just to
 * render three toolbar icons. Mirrors the Material leaf name 1:1; the report
 * toolbar references `ReportIcons.*` instead of raw `Icons.Default.*`.
 */
object ReportIcons {
    val print: ImageVector @Composable get() = Icons.Default.Print
    val save: ImageVector @Composable get() = Icons.Default.Save
    val visibility: ImageVector @Composable get() = Icons.Default.Visibility
}
