package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst

/**
 * Selection controls. The labelled variants make the WHOLE row the target
 * (one semantics node, proper Role, platform row height) - both an a11y and
 * a touch win over a bare control beside loose text. Hit-area floors come
 * from LocalMinimumInteractiveComponentSize (M3 consumes it here).
 */
@Composable
fun AppRadio(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = RadioButton(selected = selected, onClick = onClick, modifier = modifier, enabled = enabled)

@Composable
fun AppRadioRow(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .selectable(selected = selected, onClick = onClick, enabled = enabled, role = Role.RadioButton)
            .padding(vertical = AppTheme.visualTokens.listItemPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(UIConst.paddingSmall))
        AppText(label, AppTextStyle.BODY)
    }
}

@Composable
fun AppCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)

@Composable
fun AppCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .toggleable(value = checked, onValueChange = onCheckedChange, enabled = enabled, role = Role.Checkbox)
            .padding(vertical = AppTheme.visualTokens.listItemPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(UIConst.paddingSmall))
        AppText(label, AppTextStyle.BODY)
    }
}
