package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

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

/**
 * One entry of an [AppRadioColumn] / [AppCheckboxColumn]. [value] is the
 * identity the group compares and reports; [label] is what the row shows;
 * [enabled] locks a SINGLE option without disabling the whole group.
 */
@Immutable
data class AppSelectionOption<out T>(
    val value: T,
    val label: String,
    val enabled: Boolean = true,
)

/**
 * A single-choice group as the legacy GROUP BOX: the [AppGroupBox] frame
 * (caption on the border) over a DENSE vertical stack of radio rows - the
 * shape the old console groups its radios into ("Προβολή κάθε", "Break για…",
 * "Προβολή Βάσει…"). Deliberately denser than [AppRadioRow]: the legacy
 * toolbar packs ~20dp per option, so rows here drop the list-item padding,
 * label with NOTE, and shrink the control itself (see [DenseSelectionRow]).
 *
 * The box wraps its widest option (compact, like the original); give the whole
 * group a width via [modifier] if a fixed column is wanted.
 */
@Composable
fun <T> AppRadioColumn(
    options: List<AppSelectionOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    enabled: Boolean = true,
    /** How the rows sit when the box is taller than them (e.g. a stretched band). */
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
) {
    AppGroupBox(title = title, enabled = enabled, verticalArrangement = verticalArrangement, modifier = modifier) {
        options.forEach { option ->
            val rowEnabled = enabled && option.enabled
            DenseSelectionRow(
                selected = option.value == selected,
                onActivate = { onSelect(option.value) },
                label = option.label,
                enabled = rowEnabled,
                role = Role.RadioButton,
            ) { RadioButton(selected = option.value == selected, onClick = null, enabled = rowEnabled) }
        }
    }
}

/**
 * The multi-choice sibling of [AppRadioColumn]: the same titled group box, but
 * checkboxes over a [selected] set. [onToggle] reports the option and its new
 * checked state; the caller owns the set.
 */
@Composable
fun <T> AppCheckboxColumn(
    options: List<AppSelectionOption<T>>,
    selected: Set<T>,
    onToggle: (value: T, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    enabled: Boolean = true,
    /** How the rows sit when the box is taller than them (e.g. a stretched band). */
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
) {
    AppGroupBox(title = title, enabled = enabled, verticalArrangement = verticalArrangement, modifier = modifier) {
        options.forEach { option ->
            val rowEnabled = enabled && option.enabled
            val checked = option.value in selected
            DenseSelectionRow(
                selected = checked,
                onActivate = { onToggle(option.value, !checked) },
                label = option.label,
                enabled = rowEnabled,
                role = Role.Checkbox,
            ) { Checkbox(checked = checked, onCheckedChange = null, enabled = rowEnabled) }
        }
    }
}

/**
 * One dense group-box row: whole-row target (proper semantics [role]), but at
 * the legacy toolbar's pitch. Three density levers vs the labelled-row
 * components: no min interactive floor (desktop is mouse-first), the M3
 * control scaled to ~3/4 via a reduced [LocalDensity] (its 20dp glyph has no
 * size parameter - density is the clean lever, fontScale untouched), and
 * hairline vertical padding.
 */
@Composable
private fun DenseSelectionRow(
    selected: Boolean,
    onActivate: () -> Unit,
    label: String,
    enabled: Boolean,
    role: Role,
    control: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            modifier = Modifier
                .selectable(selected = selected, onClick = onActivate, enabled = enabled, role = role)
                .padding(vertical = UIConst.paddingHairline),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density * DENSE_CONTROL_SCALE, density.fontScale),
            ) { control() }
            Spacer(Modifier.width(UIConst.paddingExtraSmall))
            AppText(label, AppTextStyle.NOTE)
        }
    }
}

/** 20dp M3 control glyph × 0.75 ≈ the legacy console's ~14px radios. */
private const val DENSE_CONTROL_SCALE = 0.75f

// The BARE controls: on/off x enabled/disabled. Four states, and the disabled
// -checked one is the one that goes wrong.
@Preview
@Composable
private fun AppRadioPreview() = AppPreview {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppRadio(selected = true, onClick = {})
        AppRadio(selected = false, onClick = {})
        AppRadio(selected = true, onClick = {}, enabled = false)
        AppRadio(selected = false, onClick = {}, enabled = false)
    }
}

@Preview
@Composable
private fun AppCheckboxPreview() = AppPreview {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppCheckbox(checked = true, onCheckedChange = {})
        AppCheckbox(checked = false, onCheckedChange = {})
        AppCheckbox(checked = true, onCheckedChange = {}, enabled = false)
        AppCheckbox(checked = false, onCheckedChange = {}, enabled = false)
    }
}

// A single-choice group: one row selected, one not, one locked out.
@Preview
@Composable
private fun AppRadioRowPreview() = AppPreview {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall)) {
        AppRadioRow(selected = true, onClick = {}, label = "Crete TV")
        AppRadioRow(selected = false, onClick = {}, label = "Radio 984")
        AppRadioRow(
            selected = false,
            onClick = {},
            label = "Minoan FM (no licence on this contract)",
            enabled = false,
        )
    }
}

@Preview
@Composable
private fun AppCheckboxRowPreview() = AppPreview {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall)) {
        AppCheckboxRow(checked = true, onCheckedChange = {}, label = "Include spots that already aired")
        AppCheckboxRow(checked = false, onCheckedChange = {}, label = "Email the station traffic desk")
        AppCheckboxRow(
            checked = true,
            onCheckedChange = {},
            label = "Archive contract CTV-2025-198 (closed)",
            enabled = false,
        )
    }
}

// The legacy grouped boxes: a titled frame over a vertical radio column
// (single-choice) beside a titled checkbox column (multi-choice) - the shape
// the old console's top toolbar is built from.
@Preview
@Composable
private fun AppSelectionColumnsPreview() = AppPreview {
    Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingRegular)) {
        AppRadioColumn(
            title = "View every",
            options = listOf(
                AppSelectionOption("hour", "1 Hour"),
                AppSelectionOption("half", "Half Hour"),
                AppSelectionOption("break", "Break"),
            ),
            selected = "half",
            onSelect = {},
        )
        AppCheckboxColumn(
            title = "Show based on",
            options = listOf(
                AppSelectionOption("all", "All"),
                AppSelectionOption("program", "Programme"),
                AppSelectionOption("customer", "Customer"),
                AppSelectionOption("contract", "Contract (none on this station)", enabled = false),
            ),
            selected = setOf("all", "customer"),
            onToggle = { _, _ -> },
        )
    }
}

// The frame is the whole point, and a hairline outline reads completely
// differently on the dark palette - previewing only the light one hides that.
@Preview
@Composable
private fun AppSelectionColumnsDarkPreview() = AppPreview(dark = true) {
    AppRadioColumn(
        title = "View every",
        options = listOf(
            AppSelectionOption("hour", "1 Hour"),
            AppSelectionOption("half", "Half Hour"),
            AppSelectionOption("break", "Break"),
        ),
        selected = "break",
        onSelect = {},
    )
}
