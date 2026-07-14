package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import eu.anifantakis.commercials.core.presentation.design_system.text.nativePlatformImeOptions
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview

/**
 * THE single `OutlinedTextField` of the app (skill rule: one base field,
 * thin wrappers). It owns the shape, the platform height floor, the error
 * line (M3 supporting text) and the icon slots; [AppTextField] and
 * [AppPasswordField] delegate. Domain knowledge never lives here.
 *
 * Height: `heightIn(min = fieldHeight)` sets a non-zero incoming constraint,
 * which makes M3's own 56dp `defaultMinSize` yield - that is how desktop
 * profiles compress. How LOW it can go is the PlatformShowcase spike.
 *
 * keyboardOptions passes through untouched: the iOS native-text-input work
 * (PR 5) injects `platformImeOptions` HERE, in exactly one place.
 */
@Composable
fun AppWireframeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    errorText: String? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val t = AppTheme.visualTokens
    // iOS native text input (native caret/selection/system context menu) is
    // injected HERE, once, for every form field - unless the caller already
    // set its own platform IME options.
    val effectiveKeyboardOptions =
        if (keyboardOptions.platformImeOptions == null) {
            nativePlatformImeOptions()
                ?.let { keyboardOptions.copy(platformImeOptions = it) }
                ?: keyboardOptions
        } else keyboardOptions
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = t.fieldHeight),
        label = { AppText(label, AppTextStyle.FIELD_LABEL) },
        placeholder = placeholder?.let { { AppText(it, AppTextStyle.FIELD_LABEL) } },
        leadingIcon = leadingIcon?.let { { AppIcon(it, contentDescription = null) } },
        trailingIcon = trailingIcon,
        enabled = enabled,
        readOnly = readOnly,
        isError = isError,
        supportingText = errorText?.let { { AppText(it, AppTextStyle.ERROR_NOTE) } },
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = effectiveKeyboardOptions,
        shape = RoundedCornerShape(t.cornerSmall),
    )
}

/** Plain text input - the everyday field. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) = AppWireframeField(
    value = value,
    onValueChange = onValueChange,
    label = label,
    modifier = modifier,
    placeholder = placeholder,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    enabled = enabled,
    isError = isError,
    errorText = errorText,
    singleLine = singleLine,
    keyboardOptions = keyboardOptions,
)

/**
 * Password input: owns the masking AND the eye toggle (the idiom three
 * screens used to hand-roll), including its localized a11y label.
 */
@Composable
fun AppPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) = AppWireframeField(
    value = value,
    onValueChange = onValueChange,
    label = label,
    modifier = modifier,
    leadingIcon = leadingIcon,
    trailingIcon = {
        AppIconButton(
            label = Strings[
                if (visible) StringKey.COMMON_CD_HIDE_PASSWORD
                else StringKey.COMMON_CD_SHOW_PASSWORD,
            ],
            icon = if (visible) AppIcons.visibilityOff else AppIcons.visibility,
            onClick = onToggleVisibility,
            enabled = enabled,
        )
    },
    enabled = enabled,
    isError = isError,
    errorText = errorText,
    singleLine = true,
    visualTransformation =
        if (visible) VisualTransformation.None else PasswordVisualTransformation(),
    keyboardOptions = keyboardOptions,
)

// The base field: the states the thin wrappers cannot show - read-only, a caller
// supplied trailing slot, and multi-line.
@Preview
@Composable
private fun AppWireframeFieldPreview() = AppPreview {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
        AppWireframeField(
            value = "",
            onValueChange = {},
            label = "Spot title",
            placeholder = "Summer campaign 30s",
        )
        AppWireframeField(
            value = "Summer campaign 30s",
            onValueChange = {},
            label = "Spot title",
            trailingIcon = { AppIcon(AppIcons.clear, contentDescription = null) },
        )
        AppWireframeField(
            value = "Crete TV",
            onValueChange = {},
            label = "Station",
            readOnly = true,
            leadingIcon = AppIcons.dns,
        )
        AppWireframeField(
            value = "Two spots moved out of the 21:00 break at the customer's request.",
            onValueChange = {},
            label = "Break notes",
            singleLine = false,
        )
    }
}

@Preview
@Composable
private fun AppTextFieldPreview() = AppPreview {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
        // Empty (placeholder showing), filled, in error, and disabled: the four
        // states every form field actually lives in.
        AppTextField(
            value = "",
            onValueChange = {},
            label = "Customer",
            placeholder = "Aegean Foods",
            leadingIcon = AppIcons.person,
        )
        AppTextField(
            value = "CTV-2026-014",
            onValueChange = {},
            label = "Contract number",
            leadingIcon = AppIcons.numbers,
        )
        AppTextField(
            value = "25:00",
            onValueChange = {},
            label = "Break time",
            isError = true,
            errorText = "Not a valid time of day",
            leadingIcon = AppIcons.timer,
        )
        AppTextField(
            value = "Radio 984",
            onValueChange = {},
            label = "Station",
            enabled = false,
            leadingIcon = AppIcons.dns,
        )
    }
}

@Preview
@Composable
private fun AppPasswordFieldPreview() = AppPreview {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
        // Masked and revealed are the SAME field with the eye toggle flipped -
        // previewing one hides half the component.
        AppPasswordField(
            value = "traffic-desk-2026",
            onValueChange = {},
            label = "Password",
            visible = false,
            onToggleVisibility = {},
            leadingIcon = AppIcons.lock,
        )
        AppPasswordField(
            value = "traffic-desk-2026",
            onValueChange = {},
            label = "Password",
            visible = true,
            onToggleVisibility = {},
            leadingIcon = AppIcons.lock,
        )
        AppPasswordField(
            value = "wrong",
            onValueChange = {},
            label = "Password",
            visible = false,
            onToggleVisibility = {},
            isError = true,
            errorText = "Wrong password for this station account",
            leadingIcon = AppIcons.lock,
        )
        AppPasswordField(
            value = "traffic-desk-2026",
            onValueChange = {},
            label = "Password",
            visible = false,
            onToggleVisibility = {},
            enabled = false,
            leadingIcon = AppIcons.lock,
        )
    }
}
