package eu.anifantakis.commercials.core.presentation.string_resources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.intl.Locale
import eu.anifantakis.commercials.core.domain.preferences.AppLanguageStore

/**
 * Owns the app's active [Language]: persisted across restarts (via the
 * [AppLanguageStore] seam — KSafe impl lives in :core:data) and
 * Compose-observable (a language switch recomposes everything that reads it).
 * Koin singleton — inject it, don't construct it.
 *
 * Initial language (product spec):
 *   1. the user's previously saved choice, else
 *   2. the system locale IF it is a supported language, else
 *   3. English ([Language.FALLBACK]).
 */
class LocalizationManager(private val store: AppLanguageStore) {

    /**
     * The current language. Backed by Compose state, so composables reading it
     * (directly or via [LocalLanguage]) recompose when it changes.
     */
    var current: Language by mutableStateOf(resolveInitial())
        private set

    private fun resolveInitial(): Language =
        Language.fromCode(store.languageCode.ifEmpty { null }) // 1) saved choice
            ?: Language.fromCode(Locale.current.language)      // 2) system locale, if supported
            ?: Language.FALLBACK                               // 3) English

    /** Change and persist the app language. Triggers recomposition. */
    fun setLanguage(language: Language) {
        current = language
        store.languageCode = language.code
    }

    /** Resolve a key in the current language (non-composable path). */
    fun get(key: StringKey): String = resolve(key, current)
}
