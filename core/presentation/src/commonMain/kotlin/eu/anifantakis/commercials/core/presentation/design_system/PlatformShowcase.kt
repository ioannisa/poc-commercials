package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCard
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCheckbox
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCheckboxColumn
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCheckboxRow
import eu.anifantakis.commercials.core.presentation.design_system.components.AppDialog
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppPasswordField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadio
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadioColumn
import eu.anifantakis.commercials.core.presentation.design_system.components.AppSelectionOption
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.design_system.platform.InputCapabilities

/**
 * The design-system LABORATORY (dev-only; desktop entry:
 * `./gradlew :desktopApp:runShowcase`).
 *
 * Renders the token tables as living controls under any combination of:
 * platform profile x dark x density preference x pointer hardware x hover x
 * reduced motion x high contrast x RTL x font step x window class - so one
 * person can validate six UI profiles and the input/a11y matrix on one
 * machine. This is also where the FIELD-HEIGHT SPIKE gets decided.
 *
 * Deliberately renders RAW M3 controls styled inline from the tokens: PR 1
 * evaluates the TOKEN TABLES; the App* facade (PR 2) will swap in on top.
 */
@Composable
fun PlatformShowcase() {
    var profile by rememberSaveable { mutableStateOf(PlatformPreviewProfile.MACOS) }
    var dark by rememberSaveable { mutableStateOf(false) }
    var density by rememberSaveable { mutableStateOf(DensityPreference.AUTO) }
    var pointer by rememberSaveable { mutableStateOf(PointerSim.FINE_ONLY) }
    var reduceMotion by rememberSaveable { mutableStateOf(false) }
    var highContrast by rememberSaveable { mutableStateOf(false) }
    var rtl by rememberSaveable { mutableStateOf(false) }
    var fontStep by rememberSaveable { mutableStateOf(FontSizeStep.MEDIUM) }
    var windowSim by rememberSaveable { mutableStateOf(WindowSim.EXPANDED) }

    // The control rail lives in the HOST environment (production theme);
    // only the sample panel runs under the simulated one.
    CommercialsTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                ControlRail(
                    profile = profile, onProfile = { profile = it },
                    dark = dark, onDark = { dark = it },
                    density = density, onDensity = { density = it },
                    pointer = pointer, onPointer = { pointer = it },
                    reduceMotion = reduceMotion, onReduceMotion = { reduceMotion = it },
                    highContrast = highContrast, onHighContrast = { highContrast = it },
                    rtl = rtl, onRtl = { rtl = it },
                    fontStep = fontStep, onFontStep = { fontStep = it },
                    windowSim = windowSim, onWindowSim = { windowSim = it },
                )
                HorizontalDivider(Modifier.width(1.dp).fillMaxHeight())
                SamplePanel(
                    profile = profile, dark = dark, density = density,
                    input = pointer.capabilities,
                    a11y = AccessibilityPreferences(reduceMotion, highContrast),
                    rtl = rtl, fontStep = fontStep, windowSim = windowSim,
                )
            }
        }
    }
}

private enum class PointerSim(val label: String, val capabilities: InputCapabilities) {
    FINE_ONLY("Fine only", InputCapabilities(hasCoarsePointer = false, hasFinePointer = true, canHover = true)),
    COARSE_ONLY("Coarse only", InputCapabilities(hasCoarsePointer = true, hasFinePointer = false, canHover = false)),
    HYBRID("Hybrid", InputCapabilities(hasCoarsePointer = true, hasFinePointer = true, canHover = true)),
}

private enum class WindowSim(val label: String, val widthDp: Int?) {
    COMPACT("Compact 390", 390),
    MEDIUM("Medium 800", 800),
    EXPANDED("Expanded", null),
}

@Composable
private fun ControlRail(
    profile: PlatformPreviewProfile, onProfile: (PlatformPreviewProfile) -> Unit,
    dark: Boolean, onDark: (Boolean) -> Unit,
    density: DensityPreference, onDensity: (DensityPreference) -> Unit,
    pointer: PointerSim, onPointer: (PointerSim) -> Unit,
    reduceMotion: Boolean, onReduceMotion: (Boolean) -> Unit,
    highContrast: Boolean, onHighContrast: (Boolean) -> Unit,
    rtl: Boolean, onRtl: (Boolean) -> Unit,
    fontStep: FontSizeStep, onFontStep: (FontSizeStep) -> Unit,
    windowSim: WindowSim, onWindowSim: (WindowSim) -> Unit,
) {
    Column(
        Modifier.width(230.dp).fillMaxHeight().verticalScroll(rememberScrollState())
            .padding(UIConst.paddingCompact),
        verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall),
    ) {
        RailSection("Profile")
        PlatformPreviewProfile.entries.forEach {
            RailChip(it.name, profile == it) { onProfile(it) }
        }
        RailSection("Density")
        DensityPreference.entries.forEach {
            RailChip(it.name, density == it) { onDensity(it) }
        }
        RailSection("Pointer hardware")
        PointerSim.entries.forEach {
            RailChip(it.label, pointer == it) { onPointer(it) }
        }
        RailSection("Window")
        WindowSim.entries.forEach {
            RailChip(it.label, windowSim == it) { onWindowSim(it) }
        }
        RailSection("Font step")
        listOf(FontSizeStep.XSMALL, FontSizeStep.MEDIUM, FontSizeStep.XLARGE).forEach {
            RailChip(it.name, fontStep == it) { onFontStep(it) }
        }
        RailSection("Environment")
        RailChip("Dark", dark) { onDark(!dark) }
        RailChip("Reduce motion", reduceMotion) { onReduceMotion(!reduceMotion) }
        RailChip("High contrast", highContrast) { onHighContrast(!highContrast) }
        RailChip("RTL", rtl) { onRtl(!rtl) }
    }
}

@Composable
private fun RailSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = UIConst.paddingSmall),
    )
}

@Composable
private fun RailChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SamplePanel(
    profile: PlatformPreviewProfile,
    dark: Boolean,
    density: DensityPreference,
    input: InputCapabilities,
    a11y: AccessibilityPreferences,
    rtl: Boolean,
    fontStep: FontSizeStep,
    windowSim: WindowSim,
) {
    CommercialsTheme(
        profile = profile,
        input = input,
        darkTheme = dark,
        fontSizeStep = fontStep,
        densityPreference = density,
        a11yOverride = a11y,
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            Box(
                Modifier.fillMaxHeight()
                    .then(windowSim.widthDp?.let { Modifier.width(it.dp) } ?: Modifier.fillMaxWidth()),
            ) {
                WindowSizeProvider {
                    Surface(Modifier.fillMaxSize()) {
                        Samples()
                    }
                }
            }
        }
    }
}

@Composable
private fun Samples() {
    val t = AppTheme.visualTokens
    val interaction = AppTheme.interaction
    val a11y = AppTheme.a11y
    val window = AppTheme.window

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.screenPadding),
        verticalArrangement = Arrangement.spacedBy(UIConst.paddingRegular),
    ) {
        // Resolved policy, visible: this line is what PROVES hybrid+AUTO ->
        // compact, coarse-only+AUTO -> touch-friendly, override wins.
        Text(
            "minTarget=${interaction.minimumTargetSize}  hover=${interaction.supportsHover}  " +
                "touch=${interaction.supportsTouchGestures}  pull=${interaction.pullToRefresh}  " +
                "window=${window.width}/${window.height}  reduceMotion=${a11y.reduceMotion}  " +
                "highContrast=${a11y.highContrast}",
            style = AppTheme.typography.mono,
        )
        HorizontalDivider()

        SampleSection("AppButton (h=${t.buttonHeight})") {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppButton("Primary", onClick = {})
                AppButton("Secondary", onClick = {}, variant = AppButtonVariant.SECONDARY)
                AppButton("Text", onClick = {}, variant = AppButtonVariant.TEXT)
                AppButton("Delete", onClick = {}, variant = AppButtonVariant.DESTRUCTIVE)
                AppButton("Disabled", onClick = {}, enabled = false)
                var busy by remember { mutableStateOf(false) }
                AppButton("Busy", onClick = { busy = !busy }, busy = busy)
                AppButton("Icon", onClick = {}, leadingIcon = AppDrawableRepo.add)
                AppIconButton(label = "Settings", icon = AppDrawableRepo.settings, onClick = {})
            }
        }

        SampleSection("AppTextField / AppPasswordField (facade)") {
            Column(
                Modifier.widthIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
            ) {
                var name by remember { mutableStateOf("") }
                AppTextField(name, { name = it }, label = "Username", leadingIcon = AppDrawableRepo.person)
                var pw by remember { mutableStateOf("hunter2") }
                var pwVisible by remember { mutableStateOf(false) }
                AppPasswordField(
                    pw, { pw = it }, label = "Password",
                    visible = pwVisible, onToggleVisibility = { pwVisible = !pwVisible },
                    leadingIcon = AppDrawableRepo.lock,
                )
                var err by remember { mutableStateOf("bad value") }
                AppTextField(err, { err = it }, label = "With error", isError = true, errorText = "This is the error line")
            }
        }

        SampleSection("AppDialog") {
            var open by remember { mutableStateOf(false) }
            AppButton("Open dialog", onClick = { open = true }, variant = AppButtonVariant.SECONDARY)
            if (open) {
                AppDialog(
                    title = "Confirm action",
                    onDismiss = { open = false },
                    confirmText = "Confirm",
                    onConfirm = { open = false },
                    dismissText = "Cancel",
                    icon = AppDrawableRepo.info,
                ) {
                    AppText(
                        "Full-bleed on COMPACT, token width elsewhere, platform corner + tonal depth.",
                        AppTextStyle.BODY,
                    )
                }
            }
        }

        SampleSection("FIELD-HEIGHT SPIKE (fieldHeight token = ${t.fieldHeight}; M3 default min 56)") {
            Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
                SpikeField(label = "Default (56 floor)", heightOverride = null)
                SpikeField(label = "48dp floor", heightOverride = 48)
                SpikeField(label = "40dp floor", heightOverride = 40)
                SpikeField(label = "40dp + icons + error", heightOverride = 40, decorated = true)
            }
        }

        SampleSection("Selection (background = actual layout box incl. min-target expansion)") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var checked by remember { mutableStateOf(true) }
                var picked by remember { mutableStateOf(0) }
                Box(Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))) {
                    AppCheckbox(checked, { checked = it })
                }
                Box(Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))) {
                    AppRadio(picked == 0, { picked = 0 })
                }
                Box(Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))) {
                    AppRadio(picked == 1, { picked = 1 })
                }
                Text("boxes show the reserved interactive area", style = AppTheme.typography.statLabel)
            }
            var rowChecked by remember { mutableStateOf(true) }
            AppCheckboxRow(rowChecked, { rowChecked = it }, label = "Labelled row = whole-row target")
        }

        SampleSection("Selection groups (legacy GroupBox: titled frame + vertical column)") {
            Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingRegular)) {
                var mode by remember { mutableStateOf("half") }
                AppRadioColumn(
                    title = "View every",
                    options = listOf(
                        AppSelectionOption("hour", "1 Hour"),
                        AppSelectionOption("half", "Half Hour"),
                        AppSelectionOption("break", "Break"),
                    ),
                    selected = mode,
                    onSelect = { mode = it },
                )
                val basis = remember { mutableStateListOf("all", "customer") }
                AppCheckboxColumn(
                    title = "Show based on",
                    options = listOf(
                        AppSelectionOption("all", "All"),
                        AppSelectionOption("program", "Programme"),
                        AppSelectionOption("customer", "Customer"),
                        AppSelectionOption("contract", "Contract (locked)", enabled = false),
                    ),
                    selected = basis.toSet(),
                    onToggle = { value, checked -> if (checked) basis.add(value) else basis.remove(value) },
                )
            }
        }

        // A stand-in for the Timetable header's legacy grouped-box toolbar, so
        // its density/frames can be eyeballed here (the real one is behind
        // login). Mirrors Row A: real Μηνύματα + Προβολή κάθε boxes, "pending"
        // stubs for the not-yet-migrated features, compacted via the min-target
        // override just like the header.
        SampleSection("Legacy toolbar (mock of the Timetable header)") {
            LegacyToolbarMock()
        }

        SampleSection("AppCard (elevation=${t.cardElevation} border=${t.cardBorderWidth})") {
            AppCard {
                Column(Modifier.padding(t.cardPadding)) {
                    Text("Card title", style = AppTheme.typography.sectionTitle)
                    Text(
                        "Elevation XOR border is the platform tell: Android floats, " +
                            "iOS/macOS hairline, Windows/Linux/web stroke.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        SampleSection("Motion (medium=${t.motion.mediumMillis}ms${if (a11y.reduceMotion) " -> SNAP" else ""})") {
            var moved by remember { mutableStateOf(false) }
            val offset by animateDpAsState(
                targetValue = if (moved) 160.dp else 0.dp,
                animationSpec = t.motion.mediumSpec(a11y),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(UIConst.paddingRegular),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppButton("Animate", onClick = { moved = !moved }, variant = AppButtonVariant.TEXT)
                Box(
                    Modifier.offset(x = offset).size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(t.cornerSmall)),
                )
            }
        }

        SampleSection("Form width (formMaxWidth=${t.formMaxWidth}, full-bleed on COMPACT)") {
            val formModifier =
                if (window.isCompact) Modifier.fillMaxWidth()
                else Modifier.widthIn(max = t.formMaxWidth).fillMaxWidth()
            Box(
                formModifier
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(t.cornerSmall))
                    .padding(UIConst.paddingSmall),
            ) { Text("form column bounds", style = AppTheme.typography.statLabel) }
        }
    }
}

@Composable
private fun SampleSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
        Text(title, style = AppTheme.typography.sectionTitle)
        content()
    }
}

@Composable
private fun SpikeField(label: String, heightOverride: Int?, decorated: Boolean = false) {
    val t = AppTheme.visualTokens
    var value by remember { mutableStateOf("") }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = { Text(label) },
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
            .then(heightOverride?.let { Modifier.heightIn(min = it.dp) } ?: Modifier),
        leadingIcon = if (decorated) {
            { Icon(AppDrawableRepo.person, null, Modifier.size(t.iconMedium)) }
        } else null,
        trailingIcon = if (decorated) {
            { Icon(AppDrawableRepo.visibility, null, Modifier.size(t.iconMedium)) }
        } else null,
        isError = decorated,
        supportingText = if (decorated) {
            { Text("error text keeps its own line") }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(t.cornerSmall),
    )
}
