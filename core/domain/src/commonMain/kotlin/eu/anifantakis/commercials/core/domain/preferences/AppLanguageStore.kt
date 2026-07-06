package eu.anifantakis.commercials.core.domain.preferences

/**
 * Persists the user's chosen UI language as an ISO-639 code (empty = not yet
 * chosen, fall back to system locale). Domain contract so the presentation
 * [LocalizationManager] depends on an abstraction; the KSafe implementation
 * lives in :core:data (presentation never sees the persistence library).
 */
interface AppLanguageStore {
    var languageCode: String
}
