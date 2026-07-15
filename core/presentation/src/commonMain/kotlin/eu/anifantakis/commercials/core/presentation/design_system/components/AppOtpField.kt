package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview

/**
 * A [length]-digit numeric OTP: ONE focusable field, drawn as [length] boxes.
 *
 * Digits only (no letters - a Greek/English layout can't mistype it); pasting a
 * full code fills every box; the active (next-to-fill) box is highlighted. The
 * real caret/text is transparent - the boxes ARE the UI, and the single field
 * keeps focus/backspace/paste behaving like one input. Geometry is
 * platform-adaptive via [AppTheme.visualTokens].
 */
@Composable
fun AppOtpField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 6,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    val digits = value.filter { it.isDigit() }.take(length)
    val t = AppTheme.visualTokens
    val scheme = MaterialTheme.colorScheme

    BasicTextField(
        value = TextFieldValue(digits, selection = TextRange(digits.length)),
        onValueChange = { tfv -> onValueChange(tfv.text.filter { it.isDigit() }.take(length)) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = TextStyle(color = Color.Transparent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
                repeat(length) { index ->
                    val char = digits.getOrNull(index)
                    val isActive = enabled && index == digits.length && digits.length < length
                    val borderColor = when {
                        isError -> scheme.error
                        isActive -> scheme.primary
                        char != null -> scheme.outline
                        else -> scheme.outlineVariant
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(t.fieldHeight)
                            .border(
                                width = if (isActive || isError) t.controlBorderWidth * 2 else t.controlBorderWidth,
                                color = borderColor,
                                shape = RoundedCornerShape(t.cornerSmall),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppText(
                            text = char?.toString().orEmpty(),
                            style = AppTextStyle.SECTION_TITLE,
                            textAlign = TextAlign.Center,
                            color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
            }
        },
    )
}

@Preview
@Composable
private fun AppOtpFieldPreview() = AppPreview {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingRegular)) {
        // Partially filled (an active box), complete, and an error state.
        AppOtpField(value = "123", onValueChange = {})
        AppOtpField(value = "123456", onValueChange = {})
        AppOtpField(value = "12", onValueChange = {}, isError = true)
    }
}
