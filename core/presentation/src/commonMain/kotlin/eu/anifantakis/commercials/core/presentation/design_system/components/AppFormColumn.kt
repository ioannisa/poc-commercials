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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

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

// The default cap: on a wide window the form stays a readable column instead of
// stretching a login box across a 27" monitor.
@Preview
@Composable
private fun AppFormColumnPreview() = AppPreview {
    AppFormColumn(verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
        AppText("Book a spot", AppTextStyle.SECTION_TITLE)
        AppTextField(value = "Aegean Foods", onValueChange = {}, label = "Customer", leadingIcon = AppDrawableRepo.person)
        AppTextField(value = "Crete TV", onValueChange = {}, label = "Station", leadingIcon = AppDrawableRepo.dns)
        AppTextField(value = "21:00", onValueChange = {}, label = "Break", leadingIcon = AppDrawableRepo.timer)
        AppButton(text = "Confirm booking", onClick = {}, fillMaxWidth = true)
    }
}

// An explicit, narrower cap - the literal stays visible at the call site.
@Preview
@Composable
private fun AppFormColumnNarrowPreview() = AppPreview {
    AppFormColumn(
        maxWidth = 280.dp,
        verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
    ) {
        AppText("Sign in", AppTextStyle.SECTION_TITLE)
        AppTextField(value = "traffic@crete-tv.example", onValueChange = {}, label = "Email", leadingIcon = AppDrawableRepo.email)
        AppPasswordField(
            value = "traffic-desk-2026",
            onValueChange = {},
            label = "Password",
            visible = false,
            onToggleVisibility = {},
            leadingIcon = AppDrawableRepo.lock,
        )
        AppButton(text = "Sign in", onClick = {}, fillMaxWidth = true)
    }
}
