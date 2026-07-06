package eu.anifantakis.commercials

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import eu.anifantakis.commercials.navigation.NavigationRoot
import eu.anifantakis.commercials.feature.preferences.domain.ThemePreference
import eu.anifantakis.commercials.feature.preferences.domain.UserPreferences
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.string_resources.LocalLanguage
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationManager
import org.koin.compose.koinInject

@Composable
fun App() {

    val prefs = koinInject<UserPreferences>()
    // App language (persisted, Compose-observable). Reading `current` here means
    // a switch recomposes the whole tree, so every Strings[...] re-resolves.
    val localization = koinInject<LocalizationManager>()

    WithTextPrefetch {
      CompositionLocalProvider(LocalLanguage provides localization.current) {
        // Theme preference (Preferences screen, persisted in KSafe): explicit
        // Light/Dark, or System = follow the OS/browser. Applied live - the
        // preference is Compose state. The scheduler interior stays light by
        // design (programme colours are data; see grids/GridTheme.kt).
        val dark = when (prefs.theme) {
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
        }
        AppTheme(darkTheme = dark) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .safeContentPadding()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    NavigationRoot()
                }
            }
        }
      }
    }
}
