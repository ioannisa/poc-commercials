package eu.anifantakis.commercials.prefs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.invoke
import org.koin.core.annotation.Provided

/** How the app resolves light/dark. SYSTEM follows the OS/browser setting. */
enum class ThemePreference { LIGHT, DARK, SYSTEM }

/**
 * User-local preferences, persisted in KSafe (same encrypted store as the
 * session) and exposed as Compose-observable state - assigning [theme]
 * restyles the whole app instantly AND survives restarts.
 *
 * Koin singleton. KSafe is @Provided (classic-DSL factory definition, same
 * as AuthSession).
 */
class UserPreferences(@Provided private val ksafe: KSafe) {

    // Stored as the enum NAME so adding future preferences can't corrupt it;
    // unknown values (from a downgrade) safely fall back to SYSTEM.
    private var stored by ksafe(ThemePreference.SYSTEM.name, key = "theme_preference")

    private var themeState by mutableStateOf(
        runCatching { ThemePreference.valueOf(stored) }.getOrDefault(ThemePreference.SYSTEM)
    )

    var theme: ThemePreference
        get() = themeState
        set(value) {
            themeState = value
            stored = value.name
        }
}
