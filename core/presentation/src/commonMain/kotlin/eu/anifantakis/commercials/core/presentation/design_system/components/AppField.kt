package eu.anifantakis.commercials.core.presentation.design_system.components

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
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import androidx.compose.ui.graphics.vector.ImageVector

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
        keyboardOptions = keyboardOptions,
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
