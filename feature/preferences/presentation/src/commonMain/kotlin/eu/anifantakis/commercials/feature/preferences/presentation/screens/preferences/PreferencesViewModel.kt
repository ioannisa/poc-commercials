package eu.anifantakis.commercials.feature.preferences.presentation.screens.preferences

import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import androidx.compose.runtime.Stable
import eu.anifantakis.commercials.core.presentation.string_resources.Language
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationManager
import eu.anifantakis.commercials.feature.preferences.domain.ThemePreference
import eu.anifantakis.commercials.feature.preferences.domain.UserPreferences

sealed interface PreferencesIntent {
    data class ThemeSelected(val theme: ThemePreference) : PreferencesIntent
    data class LanguageSelected(val language: Language) : PreferencesIntent
}

/**
 * The gear screen's ViewModel. [theme] and [language] delegate to
 * Compose-snapshot-backed sources (the domain prefs contract and the
 * LocalizationManager), so composables reading them recompose live when the
 * selection changes.
 */
@Stable
class PreferencesViewModel(
    private val prefs: UserPreferences,
    private val localization: LocalizationManager,
) : BaseGlobalViewModel() {

    val theme: ThemePreference get() = prefs.theme
    val language: Language get() = localization.current

    fun onAction(intent: PreferencesIntent) {
        when (intent) {
            is PreferencesIntent.ThemeSelected -> prefs.theme = intent.theme
            is PreferencesIntent.LanguageSelected -> localization.setLanguage(intent.language)
        }
    }
}
