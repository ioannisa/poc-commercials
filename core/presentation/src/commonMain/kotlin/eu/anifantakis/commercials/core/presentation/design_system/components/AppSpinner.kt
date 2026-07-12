package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme

/**
 * The small inline progress spinner (the "button is busy" idiom that used
 * to be a hand-rolled `CircularProgressIndicator(size(18.dp), strokeWidth=2)`
 * at eleven call sites). Inherits the enclosing content color so it stays
 * legible inside buttons in every state. NOT the blocking overlay - that is
 * [AppLoadingIndicator].
 */
@Composable
fun AppSpinner(
    modifier: Modifier = Modifier,
    size: AppIconSize = AppIconSize.SMALL,
    color: Color = LocalContentColor.current,
) {
    val t = AppTheme.visualTokens
    val sizeDp = when (size) {
        AppIconSize.SMALL -> t.iconSmall
        AppIconSize.MEDIUM -> t.iconMedium
        AppIconSize.LARGE -> t.iconLarge
    }
    CircularProgressIndicator(
        modifier = modifier.size(sizeDp),
        color = color,
        strokeWidth = 2.dp,
    )
}
