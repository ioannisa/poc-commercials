package eu.anifantakis.commercials

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.auth.AppRole
import eu.anifantakis.commercials.auth.AuthSession
import eu.anifantakis.commercials.db.DbDemoButton
import eu.anifantakis.commercials.navigation.RootNavigation
import org.koin.compose.koinInject

@Composable
fun App() {
    val authSession = koinInject<AuthSession>()

    WithTextPrefetch {
        MaterialTheme {
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
