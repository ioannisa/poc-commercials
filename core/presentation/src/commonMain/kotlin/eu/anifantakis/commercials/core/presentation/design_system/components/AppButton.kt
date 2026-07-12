package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst

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
    fillMaxWidth: Boolean = false,
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
    val clickable = enabled && !busy

    val body: @Composable RowScope.() -> Unit = {
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
        AppText(
            text,
            if (variant == AppButtonVariant.PRIMARY) AppTextStyle.BUTTON_STRONG else AppTextStyle.BUTTON,
        )
    }

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
