package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

/**
 * The four button roles of the app. The `when` inside AppButton branches on
 * THIS - never on the platform: flatness on pointer platforms comes from the
 * elevation token being zero, size from the height token, and the touch
 * hit-area from the interaction policy.
 */
enum class AppButtonVariant { PRIMARY, SECONDARY, TEXT, DESTRUCTIVE }

/**
 * The design-system button.
 *
 * M3's `Button` does NOT consume `LocalMinimumInteractiveComponentSize`, so
 * the interactive floor is applied explicitly here: on a touch-friendly
 * session a compact-looking button still sits inside a >=48dp interactive
 * box (the modifier only expands layout space - it never changes the visual).
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.PRIMARY,
    enabled: Boolean = true,
    /** Shows an inline spinner and disables the button (submit-in-flight). */
    busy: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    fillMaxWidth: Boolean = false,
) = AppButton(
    onClick = onClick,
    modifier = modifier,
    variant = variant,
    enabled = enabled && !busy,
    fillMaxWidth = fillMaxWidth,
) {
    when {
        busy -> {
            AppSpinner(size = AppIconSize.SMALL)
            Spacer(Modifier.width(UIConst.paddingSmall))
        }
        leadingIcon != null -> {
            AppIcon(leadingIcon, contentDescription = null, size = AppIconSize.SMALL)
            Spacer(Modifier.width(UIConst.paddingExtraSmall))
        }
    }
    // A button label is one line, always: squeezed, free-wrapping text breaks
    // mid-word («Πρ ός θε ση») and drags the button's height with it. If a
    // label does not fit, the answer is a wider button or a shorter label -
    // never a four-line one.
    AppText(
        text,
        if (variant == AppButtonVariant.PRIMARY) AppTextStyle.BUTTON_STRONG else AppTextStyle.BUTTON,
        softWrap = false,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (trailingIcon != null && !busy) {
        Spacer(Modifier.width(UIConst.paddingExtraSmall))
        AppIcon(trailingIcon, contentDescription = null, size = AppIconSize.SMALL)
    }
}

/**
 * Content-slot overload for the anchor/custom cases the text overload can't
 * express (a dropdown anchor with a truncating label + caret, a two-part
 * label). Still token-styled - shape, height, padding, elevation and the
 * interactive floor all apply; only the CONTENT is the caller's.
 */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.SECONDARY,
    enabled: Boolean = true,
    fillMaxWidth: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val t = AppTheme.visualTokens
    val shape = RoundedCornerShape(t.cornerSmall)
    val padding = PaddingValues(
        horizontal = t.buttonPaddingHorizontal,
        vertical = t.buttonPaddingVertical,
    )
    val sized = modifier
        .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
        .minimumInteractiveComponentSize()
        .heightIn(min = t.buttonHeight)
    val clickable = enabled
    val body = content

    when (variant) {
        AppButtonVariant.PRIMARY -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = clickable,
            shape = shape,
            // null elevation = genuinely FLAT: how every pointer platform
            // gets its look without a per-OS component.
            elevation = if (t.buttonElevation > 0.dp)
                ButtonDefaults.buttonElevation(defaultElevation = t.buttonElevation) else null,
            contentPadding = padding,
            content = body,
        )
        AppButtonVariant.SECONDARY -> OutlinedButton(
            onClick = onClick,
            modifier = sized,
            enabled = clickable,
            shape = shape,
            border = BorderStroke(
                // High contrast: never a hairline stroke.
                width = if (AppTheme.a11y.highContrast) maxOf(t.controlBorderWidth, 1.5.dp)
                else t.controlBorderWidth,
                color = MaterialTheme.colorScheme.outline,
            ),
            contentPadding = padding,
            content = body,
        )
        AppButtonVariant.TEXT -> TextButton(
            onClick = onClick,
            modifier = sized,
            enabled = clickable,
            shape = shape,
            contentPadding = padding,
            content = body,
        )
        AppButtonVariant.DESTRUCTIVE -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = clickable,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            elevation = if (t.buttonElevation > 0.dp)
                ButtonDefaults.buttonElevation(defaultElevation = t.buttonElevation) else null,
            contentPadding = padding,
            content = body,
        )
    }
}

@Preview
@Composable
private fun AppButtonVariantsPreview() = AppPreview {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
        AppButton(text = "Save spot", onClick = {}, variant = AppButtonVariant.PRIMARY)
        AppButton(text = "Add break", onClick = {}, variant = AppButtonVariant.SECONDARY)
        AppButton(text = "Cancel", onClick = {}, variant = AppButtonVariant.TEXT)
        AppButton(text = "Delete contract", onClick = {}, variant = AppButtonVariant.DESTRUCTIVE)
    }
}

@Preview
@Composable
private fun AppButtonStatesPreview() = AppPreview {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
        AppButton(text = "Send schedule", onClick = {})
        AppButton(text = "Send schedule", onClick = {}, enabled = false)
        // Busy: the spinner replaces the leading icon and the click is locked.
        AppButton(text = "Sending to Crete TV", onClick = {}, busy = true)
        AppButton(text = "New customer", onClick = {}, leadingIcon = AppDrawableRepo.add)
        AppButton(
            text = "Radio 984",
            onClick = {},
            variant = AppButtonVariant.SECONDARY,
            trailingIcon = AppDrawableRepo.arrowDropDown,
        )
        AppButton(text = "Confirm booking", onClick = {}, fillMaxWidth = true)
    }
}

@Preview
@Composable
private fun AppButtonContentSlotPreview() = AppPreview {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
        // The dropdown-anchor case the text overload cannot express: a label that
        // truncates, then a caret pinned to the trailing edge.
        AppButton(onClick = {}, variant = AppButtonVariant.SECONDARY) {
            AppText(
                "Crete TV - main transmitter",
                AppTextStyle.BUTTON,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(UIConst.paddingExtraSmall))
            AppIcon(AppDrawableRepo.arrowDropDown, contentDescription = null, size = AppIconSize.SMALL)
        }
        AppButton(onClick = {}, variant = AppButtonVariant.SECONDARY, enabled = false) {
            AppText("No station selected", AppTextStyle.BUTTON)
        }
    }
}
