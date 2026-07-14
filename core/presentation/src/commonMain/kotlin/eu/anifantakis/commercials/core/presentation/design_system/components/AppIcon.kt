package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

/**
 * Semantic icon sizes - the platform decides the dp (desktop icons are
 * visibly smaller than mobile ones; see PlatformVisualTokens).
 */
enum class AppIconSize { SMALL, MEDIUM, LARGE }

/**
 * The design-system icon. Vectors come from AppIcons (single-door rule).
 *
 * [contentDescription] defaults to null ON PURPOSE: a decorative icon must
 * NOT carry a label, or screen readers announce it twice and litter the
 * tree with junk nodes. The a11y label belongs on the ACTIONABLE control -
 * [AppIconButton] makes its label mandatory instead.
 */
@Composable
fun AppIcon(
    imageVector: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: AppIconSize = AppIconSize.MEDIUM,
    tint: Color = LocalContentColor.current,
) {
    val t = AppTheme.visualTokens
    val sizeDp = when (size) {
        AppIconSize.SMALL -> t.iconSmall
        AppIconSize.MEDIUM -> t.iconMedium
        AppIconSize.LARGE -> t.iconLarge
    }
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier.size(sizeDp),
        tint = tint,
    )
}

// The three semantic sizes side by side: the ONLY way to see that the step
// between them is a platform token and not a dp the caller picked.
@Preview
@Composable
private fun AppIconPreview() = AppPreview {
    Row(
        horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(AppIcons.dns, contentDescription = null, size = AppIconSize.SMALL)
        AppIcon(AppIcons.dns, contentDescription = null, size = AppIconSize.MEDIUM)
        AppIcon(AppIcons.dns, contentDescription = null, size = AppIconSize.LARGE)
    }
}

// Tinting: default (inherited content color), a status green, and an error red.
@Preview
@Composable
private fun AppIconTintedPreview() = AppPreview {
    Row(
        horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(AppIcons.timer, contentDescription = null)
        AppIcon(
            AppIcons.check,
            contentDescription = "Spot aired",
            tint = MaterialTheme.colorScheme.primary,
        )
        AppIcon(
            AppIcons.delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}
