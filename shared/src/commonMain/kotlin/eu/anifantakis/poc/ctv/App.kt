package eu.anifantakis.poc.ctv

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
import eu.anifantakis.poc.ctv.db.DbDemoButton
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
                Box(modifier = Modifier.fillMaxSize()) {
                    RootNavigation()

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
