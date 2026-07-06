package eu.anifantakis.commercials.core.presentation.string_resources

/**
 * Every user-facing string is a key here, resolved to the current language via
 * [Strings] (composable) or [localized] (non-composable). Grouped by area.
 *
 * RULE: adding a key means adding it to BOTH language maps (lang/StringsEl.kt,
 * lang/StringsEn.kt) — the ArchitectureTest fitness function guards that every
 * key exists in every language. Args are positional placeholders `{0}`, `{1}`.
 */
enum class StringKey {

    // ── Data / network errors ────────────────────────────────────────────
    ERROR_NO_INTERNET,
    ERROR_SESSION_EXPIRED,
    ERROR_FORBIDDEN,
    ERROR_NOT_FOUND,
    ERROR_CONFLICT_REFRESH,
    ERROR_SERIALIZATION,
    ERROR_SERVER,                 // {0} = error name
    ERROR_LOCAL_STORAGE,

    // ── Auth ─────────────────────────────────────────────────────────────
    AUTH_INVALID_CREDENTIALS,
    AUTH_NO_STATIONS_ASSIGNED,
    AUTH_NOT_LOGGED_IN,
    AUTH_NETWORK_UNREACHABLE,

    // ── Preferences ──────────────────────────────────────────────────────
    PREFERENCES_LANGUAGE,
}
