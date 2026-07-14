package eu.anifantakis.commercials

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import eu.anifantakis.commercials.navigation.NavigationRoot
import eu.anifantakis.commercials.feature.preferences.domain.FontSizePreference
import eu.anifantakis.commercials.feature.preferences.domain.ThemePreference
import eu.anifantakis.commercials.feature.preferences.domain.UserPreferences
import eu.anifantakis.commercials.core.domain.preferences.AppLanguageStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.CommercialsTheme
import eu.anifantakis.commercials.core.presentation.design_system.FontSizeStep
import eu.anifantakis.commercials.core.presentation.design_system.WindowSizeProvider
import eu.anifantakis.commercials.grids.GridInputConfig
import eu.anifantakis.commercials.grids.LocalGridInput
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationManager
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationProvider
import org.koin.compose.koinInject
import eu.anifantakis.commercials.core.presentation.design_system.LocalGlyphFallback
import eu.anifantakis.commercials.grids.LocalGridTextAnnotator
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.data.session.SessionKeepAlive

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

    // THE SESSION'S PULSE. A token's window is slid forward by USE, so an app left
    // open and idle would age out and die on screen. This rotates it at launch and
    // beats while we run, so a session can only lapse while the app is CLOSED -
    // which is the one moment a re-login costs nobody any work. See SessionKeepAlive.
    //
    // Keyed on logged-in-ness, NOT on the revision: a station switch bumps the
    // revision too, and restarting the pulse there would rotate the token on every
    // switch for nothing.
    val session = koinInject<AuthSession>()
    val keepAlive = koinInject<SessionKeepAlive>()
    val revision by session.revision.collectAsState()
    val loggedIn = remember(revision) { session.isLoggedIn }
    LaunchedEffect(loggedIn) {
        if (loggedIn) keepAlive.run()
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
                // Inside safeContentPadding so the window class measures the
                // area content can actually use (excludes system bars).
                WindowSizeProvider {
                    // The grid toolkit is a leaf - it can't read the design
                    // system, so the app shell injects its input tuning here,
                    // derived from the SAME interaction policy everything
                    // else reads (never from the OS).
                    val interaction = AppTheme.interaction
                    // ...and its glyph fallback, for the same reason. Roboto has
                    // no Hebrew (nor Chinese, nor Arabic), and the grids draw most
                    // of this app's text: without this, those scripts would render
                    // everywhere EXCEPT the screen people actually work in.
                    val glyphFallback = LocalGlyphFallback.current
                    // REMEMBERED. `glyphFallback::annotateOrNull` allocates a NEW
                    // function object every pass, and LocalGridTextAnnotator is a
                    // static local: a new value there recomposes the entire tree
                    // beneath it. Bound method references happen to have equals on
                    // the JVM - nothing promises that on wasm, and a window resize
                    // recomposes this. Pin the instance and the question goes away.
                    val annotate = remember(glyphFallback) { glyphFallback::annotateOrNull }
                    CompositionLocalProvider(
                        LocalGridInput provides GridInputConfig(
                            minScale = if (interaction.supportsTouchGestures) 1.3f else 1f,
                            handleSlop = if (interaction.supportsTouchGestures) 14.dp else 0.dp,
                        ),
                        LocalGridTextAnnotator provides annotate,
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            NavigationRoot()
                        }
                    }
                }
            }
        }
      }
    }
}
