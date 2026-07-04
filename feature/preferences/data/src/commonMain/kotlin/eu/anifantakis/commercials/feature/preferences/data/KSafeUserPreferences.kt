package eu.anifantakis.commercials.feature.preferences.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    // Legacy popup-menu option "spots count / spots times": grid cells show
    // either the spot COUNT or the cell's summed spot TIME (342s -> 05:42).
    private var storedShowTimes by ksafe(false, key = "grid_show_spot_times")

    private var showTimesState by mutableStateOf(storedShowTimes)

    override var showSpotTimes: Boolean
        get() = showTimesState
        set(value) {
            showTimesState = value
            storedShowTimes = value
        }
}
