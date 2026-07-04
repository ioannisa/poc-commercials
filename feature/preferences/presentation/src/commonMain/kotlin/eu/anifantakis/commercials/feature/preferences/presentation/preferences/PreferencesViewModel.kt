package eu.anifantakis.commercials.feature.preferences.presentation.preferences

import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.feature.preferences.domain.ThemePreference
import eu.anifantakis.commercials.feature.preferences.domain.UserPreferences

sealed interface PreferencesIntent {
    data class ThemeSelected(val theme: ThemePreference) : PreferencesIntent
}

/**
 * The gear screen's ViewModel. [theme] delegates to the domain contract,
 * whose implementation is Compose-snapshot-backed - composables reading it
 * through here recompose live when the selection changes.
 */
class PreferencesViewModel(
    private val prefs: UserPreferences,
) : BaseGlobalViewModel() {

    val theme: ThemePreference get() = prefs.theme

    fun onAction(intent: PreferencesIntent) {
        when (intent) {
            is PreferencesIntent.ThemeSelected -> prefs.theme = intent.theme
        }
    }
}
