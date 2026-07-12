package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isUnspecified
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme

/**
 * The centred-form idiom (`widthIn(max=420/560).fillMaxWidth()`): on
 * COMPACT windows it goes full-bleed; elsewhere it caps at the platform's
 * form width (or an explicit [maxWidth] where a screen genuinely needs its
 * own - keep the literal visible at the call site, don't bury it).
 */
@Composable
fun AppFormColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = Dp.Unspecified,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cap = if (maxWidth.isUnspecified) AppTheme.visualTokens.formMaxWidth else maxWidth
    val widthModifier =
        if (AppTheme.window.isCompact) Modifier.fillMaxWidth()
        else Modifier.widthIn(max = cap).fillMaxWidth()
    Column(
        modifier = modifier.then(widthModifier),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
