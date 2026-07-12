package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme

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
