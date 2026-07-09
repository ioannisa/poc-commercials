package eu.anifantakis.commercials

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import eu.anifantakis.commercials.navigation.NavigationRoot
import eu.anifantakis.commercials.feature.preferences.domain.FontSizePreference
import eu.anifantakis.commercials.feature.preferences.domain.ThemePreference
import eu.anifantakis.commercials.feature.preferences.domain.UserPreferences
import eu.anifantakis.commercials.core.domain.preferences.AppLanguageStore
import eu.anifantakis.commercials.core.presentation.design_system.CommercialsTheme
import eu.anifantakis.commercials.core.presentation.design_system.FontSizeStep
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationManager
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationProvider
import org.koin.compose.koinInject

@Composable
fun App() {

    val prefs = koinInject<UserPreferences>()
    // App language: seed the LocalizationManager from its single persisted KSafe
    // entry once at startup (saved → system → EN); LocalizationProvider re-renders
    // the tree on a switch. The manager stays pure; persistence is at the edges.
    val languageStore = koinInject<AppLanguageStore>()
    LaunchedEffect(Unit) {
        LocalizationManager.setLanguage(LocalizationManager.resolveStartup(languageStore.languageCode))
    }

    WithTextPrefetch {
      LocalizationProvider {
        // Theme preference (Preferences screen, persisted in KSafe): explicit
        // Light/Dark, or System = follow the OS/browser. Applied live - the
        // preference is Compose state. The scheduler interior stays light by
        // design (programme colours are data; see grids/GridTheme.kt).
        val dark = when (prefs.theme) {
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
        }
        // Text size (Preferences slider, persisted in KSafe): the domain
        // preference maps onto the theme's step; applied live like the theme.
        val fontStep = when (prefs.fontSize) {
            FontSizePreference.XSMALL -> FontSizeStep.XSMALL
            FontSizePreference.SMALL -> FontSizeStep.SMALL
            FontSizePreference.MEDIUM -> FontSizeStep.MEDIUM
            FontSizePreference.LARGE -> FontSizeStep.LARGE
            FontSizePreference.XLARGE -> FontSizeStep.XLARGE
        }
        CommercialsTheme(darkTheme = dark, fontSizeStep = fontStep) {
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
