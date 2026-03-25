package eu.anifantakis.poc.ctv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.anifantakis.poc.ctv.navigation.RootNavigation

@Composable
fun App() {
    WithTextPrefetch {
        MaterialTheme {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .safeContentPadding()
            ) {
                RootNavigation()
            }
        }
    }
}
