package eu.anifantakis.commercials.feature.preferences.domain

/** How the app resolves light/dark. SYSTEM follows the OS/browser setting. */
enum class ThemePreference { LIGHT, DARK, SYSTEM }

/**
 * User-local preferences. Pure domain contract; the data implementation
 * backs these vars with Compose snapshot state + encrypted KSafe, so
 * composables reading THROUGH the interface restyle live AND the choice
 * survives restarts.
 */
interface UserPreferences {
    var theme: ThemePreference
}
