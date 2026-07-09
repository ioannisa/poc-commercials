package eu.anifantakis.commercials.feature.preferences.presentation.screens.preferences

import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import androidx.compose.runtime.Stable
import eu.anifantakis.commercials.core.domain.preferences.AppLanguageStore
import eu.anifantakis.commercials.core.presentation.string_resources.Language
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationManager
import eu.anifantakis.commercials.feature.preferences.domain.FontSizePreference
import eu.anifantakis.commercials.feature.preferences.domain.ThemePreference
import eu.anifantakis.commercials.feature.preferences.domain.UserPreferences

sealed interface PreferencesIntent {
    data class ThemeSelected(val theme: ThemePreference) : PreferencesIntent
    data class LanguageSelected(val language: Language) : PreferencesIntent
    data class FontSizeSelected(val size: FontSizePreference) : PreferencesIntent
}

/**
 * The gear screen's ViewModel. [theme] delegates to the Compose-snapshot-backed
 * prefs contract. Language switches the global (in-memory) [LocalizationManager]
 * AND persists the choice — the single KSafe entry behind [AppLanguageStore]
 * (persistence lives here at the edge, not inside the manager). The screen reads
 * the current language from the composition (LocalLanguage), so no getter here.
 */
@Stable
class PreferencesViewModel(
    private val prefs: UserPreferences,
    private val languageStore: AppLanguageStore,
) : BaseGlobalViewModel() {

    val theme: ThemePreference get() = prefs.theme
    val fontSize: FontSizePreference get() = prefs.fontSize

    fun onAction(intent: PreferencesIntent) {
        when (intent) {
            is PreferencesIntent.ThemeSelected -> prefs.theme = intent.theme
            is PreferencesIntent.LanguageSelected -> {
                languageStore.languageCode = intent.language.code   // persist (KSafe)
                LocalizationManager.setLanguage(intent.language)     // switch (recomposes)
            }
            is PreferencesIntent.FontSizeSelected -> prefs.fontSize = intent.size
        }
    }
}
