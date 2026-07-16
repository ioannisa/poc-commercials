package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

/**
 * Icon-only action. [label] is MANDATORY and localized: it is the a11y name
 * of the control (and, once tooltips land, its hover/long-press tooltip).
 * The inner icon is decorative (`contentDescription = null`) - labelling the
 * ACTIONABLE node once is the rule; labelling both reads twice.
 *
 * Hit area: M3 IconButton consumes LocalMinimumInteractiveComponentSize,
 * which CommercialsTheme feeds from the interaction policy - no explicit
 * floor needed here.
 */
@Composable
fun AppIconButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: AppIconSize = AppIconSize.MEDIUM,
    tint: Color = Color.Unspecified,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = label },
        enabled = enabled,
    ) {
        AppIcon(
            imageVector = icon,
            contentDescription = null,
            size = size,
            tint = if (tint != Color.Unspecified) tint else LocalContentColor.current,
        )
    }
}

// A row-action cluster: enabled, disabled (a spot that has already aired cannot
// be edited), and the destructive tint.
@Preview
@Composable
private fun AppIconButtonPreview() = AppPreview {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(label = "Edit spot", icon = AppDrawableRepo.edit, onClick = {})
        AppIconButton(label = "Edit spot", icon = AppDrawableRepo.edit, onClick = {}, enabled = false)
        AppIconButton(
            label = "Delete break",
            icon = AppDrawableRepo.delete,
            onClick = {},
            tint = MaterialTheme.colorScheme.error,
        )
        AppIconButton(label = "More actions", icon = AppDrawableRepo.moreVert, onClick = {})
    }
}

@Preview
@Composable
private fun AppIconButtonSizesPreview() = AppPreview {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(label = "Refresh schedule", icon = AppDrawableRepo.refresh, onClick = {}, size = AppIconSize.SMALL)
        AppIconButton(label = "Refresh schedule", icon = AppDrawableRepo.refresh, onClick = {}, size = AppIconSize.MEDIUM)
        AppIconButton(label = "Refresh schedule", icon = AppDrawableRepo.refresh, onClick = {}, size = AppIconSize.LARGE)
    }
}
