package eu.anifantakis.commercials.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import eu.anifantakis.commercials.core.presentation.commands.AppCommand
import eu.anifantakis.commercials.core.presentation.commands.CommandRegistry
import eu.anifantakis.commercials.core.presentation.design_system.platform.PrimaryShortcutModifier
import eu.anifantakis.commercials.core.presentation.design_system.platform.desktopPlatformCapabilities
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationManager
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.localized

/**
 * The desktop application menu. On macOS `configureSwingGlobalsForCompose`
 * (set by the Compose Gradle plugin for run + packaged apps) enables
 * `apple.laf.useScreenMenuBar`, so this lands in the REAL screen menu bar
 * (NSMenu); on Windows/Linux it renders as the in-window menu, per platform
 * convention.
 *
 * Items render their enabled state from the CommandRegistry's observable
 * state - "Export PDF" is genuinely greyed out unless the screen that owns
 * it is composed and reports are available. The ⌘/Ctrl choice comes from
 * DesktopPlatformCapabilities (derived from the single OS-detection point).
 */
@Composable
fun FrameWindowScope.AppMenuBar(registry: CommandRegistry) {
    val caps = remember { desktopPlatformCapabilities() } ?: return
    val meta = caps.primaryShortcutModifier == PrimaryShortcutModifier.META
    fun shortcut(key: Key, shift: Boolean = false) =
        KeyShortcut(key, meta = meta, ctrl = !meta, shift = shift)

    val states by registry.commandStates.collectAsState()
    fun enabled(c: AppCommand) = states[c]?.enabled == true

    // Recompose the whole menu on a language switch: localized() reads the
    // manager's CURRENT language, so subscribing to the flow here is what
    // makes the labels live (the read below is the recomposition trigger).
    val language by LocalizationManager.currentLanguage.collectAsState()
    @Suppress("UNUSED_EXPRESSION") language
    fun label(key: StringKey) = key.localized()

    MenuBar {
        Menu(label(StringKey.MENU_FILE)) {
            Item(
                text = label(StringKey.REPORT_EXPORT_PDF),
                shortcut = shortcut(Key.E),
                enabled = enabled(AppCommand.EXPORT_PDF),
                onClick = { registry.execute(AppCommand.EXPORT_PDF) },
            )
            Item(
                text = label(StringKey.REPORT_PRINT),
                shortcut = shortcut(Key.P),
                enabled = enabled(AppCommand.PRINT_REPORT),
                onClick = { registry.execute(AppCommand.PRINT_REPORT) },
            )
            Item(
                text = label(StringKey.REPORT_PREVIEW),
                shortcut = shortcut(Key.P, shift = true),
                enabled = enabled(AppCommand.PREVIEW_REPORT),
                onClick = { registry.execute(AppCommand.PREVIEW_REPORT) },
            )
            Separator()
            Item(
                text = label(StringKey.TIMETABLE_CD_EMAIL_SCHEDULE),
                shortcut = shortcut(Key.M),
                enabled = enabled(AppCommand.SEND_SCHEDULE_EMAIL),
                onClick = { registry.execute(AppCommand.SEND_SCHEDULE_EMAIL) },
            )
            Separator()
            Item(
                text = label(StringKey.TIMETABLE_CD_PREFERENCES),
                shortcut = shortcut(Key.Comma),
                enabled = enabled(AppCommand.PREFERENCES),
                onClick = { registry.execute(AppCommand.PREFERENCES) },
            )
        }
        Menu(label(StringKey.MENU_VIEW)) {
            Item(
                text = label(StringKey.MENU_TEXT_LARGER),
                shortcut = shortcut(Key.Equals),
                enabled = enabled(AppCommand.FONT_LARGER),
                onClick = { registry.execute(AppCommand.FONT_LARGER) },
            )
            Item(
                text = label(StringKey.MENU_TEXT_SMALLER),
                shortcut = shortcut(Key.Minus),
                enabled = enabled(AppCommand.FONT_SMALLER),
                onClick = { registry.execute(AppCommand.FONT_SMALLER) },
            )
            Item(
                text = label(StringKey.MENU_TEXT_RESET),
                shortcut = shortcut(Key.Zero),
                enabled = enabled(AppCommand.FONT_RESET),
                onClick = { registry.execute(AppCommand.FONT_RESET) },
            )
        }
        Menu(label(StringKey.MENU_ACCOUNT)) {
            Item(
                text = label(StringKey.CHPASS_TITLE),
                enabled = enabled(AppCommand.CHANGE_PASSWORD),
                onClick = { registry.execute(AppCommand.CHANGE_PASSWORD) },
            )
            Item(
                text = label(StringKey.RECOVERY_TITLE),
                enabled = enabled(AppCommand.RECOVERY_CODES),
                onClick = { registry.execute(AppCommand.RECOVERY_CODES) },
            )
            Separator()
            Item(
                text = label(StringKey.TIMETABLE_CD_LOGOUT),
                enabled = enabled(AppCommand.LOGOUT),
                onClick = { registry.execute(AppCommand.LOGOUT) },
            )
        }
    }
}
