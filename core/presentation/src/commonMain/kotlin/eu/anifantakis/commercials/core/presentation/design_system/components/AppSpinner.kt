package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

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

@Preview
@Composable
private fun AppSpinnerPreview() = AppPreview {
    Row(
        horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppSpinner(size = AppIconSize.SMALL)
        AppSpinner(size = AppIconSize.MEDIUM)
        AppSpinner(size = AppIconSize.LARGE)
        AppSpinner(color = MaterialTheme.colorScheme.primary)
        AppText("Loading breaks for Crete TV", AppTextStyle.NOTE)
    }
}

// The reason the default color is LocalContentColor: the spinner has to stay
// legible ON a filled primary button and on a destructive one, without the call
// site ever naming a color.
@Preview
@Composable
private fun AppSpinnerInButtonPreview() = AppPreview {
    Row(
        horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppButton(text = "Sending to Radio 984", onClick = {}, busy = true)
        AppButton(
            text = "Deleting break",
            onClick = {},
            variant = AppButtonVariant.DESTRUCTIVE,
            busy = true,
        )
    }
}
