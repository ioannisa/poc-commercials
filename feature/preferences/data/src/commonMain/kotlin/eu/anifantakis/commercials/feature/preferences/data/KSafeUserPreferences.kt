package eu.anifantakis.commercials.feature.preferences.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.anifantakis.commercials.feature.preferences.domain.FontSizePreference
import eu.anifantakis.commercials.feature.preferences.domain.ThemePreference
import eu.anifantakis.commercials.feature.preferences.domain.UserPreferences
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.invoke
import org.koin.core.annotation.Provided

/**
 * [UserPreferences] over KSafe (same encrypted store as the session) with a
 * Compose-observable mirror - assigning [theme] restyles the whole app
 * instantly AND survives restarts. Koin singleton.
 */
class KSafeUserPreferences(@Provided private val ksafe: KSafe) : UserPreferences {

    // Stored as the enum NAME so adding future preferences can't corrupt it;
    // unknown values (from a downgrade) safely fall back to SYSTEM.
    private var storedTheme by ksafe(ThemePreference.SYSTEM.name, key = "theme_preference")

    private var themeState by mutableStateOf(
        runCatching { ThemePreference.valueOf(storedTheme) }.getOrDefault(ThemePreference.SYSTEM)
    )

    override var theme: ThemePreference
        get() = themeState
        set(value) {
            themeState = value
            storedTheme = value.name
        }

    private var storedFontSize by ksafe(FontSizePreference.MEDIUM.name, key = "font_size_preference")

    private var fontSizeState by mutableStateOf(
        runCatching { FontSizePreference.valueOf(storedFontSize) }.getOrDefault(FontSizePreference.MEDIUM)
    )

    override var fontSize: FontSizePreference
        get() = fontSizeState
        set(value) {
            fontSizeState = value
            storedFontSize = value.name
        }

    // No Compose mirror: the panel owns its live width while dragging and
    // only commits on release, so nothing needs to recompose off this.
    override var panelWidthDp: Int by ksafe(
        UserPreferences.DEFAULT_PANEL_WIDTH_DP,
        key = "preferences_panel_width",
    )
}
