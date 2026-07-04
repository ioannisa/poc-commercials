package eu.anifantakis.commercials

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.db.DbDemoButton
import androidx.compose.foundation.isSystemInDarkTheme
import eu.anifantakis.commercials.navigation.RootNavigation
import eu.anifantakis.commercials.prefs.ThemePreference
import eu.anifantakis.commercials.prefs.UserPreferences
import eu.anifantakis.commercials.ui.theme.AppTheme
import org.koin.compose.koinInject

@Composable
fun App() {
    val authSession = koinInject<AuthSession>()

    val prefs = koinInject<UserPreferences>()

    WithTextPrefetch {
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
                    RootNavigation()

                    // DB smoke test - admin-only on the server too
                    @Suppress("UNUSED_EXPRESSION") authSession.revision  // recompose on login/logout
                    if (authSession.isLoggedIn && authSession.role == AppRole.NORMAL_USER) {
                        DbDemoButton(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}
