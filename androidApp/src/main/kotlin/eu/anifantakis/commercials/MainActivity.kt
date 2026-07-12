package eu.anifantakis.commercials

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.data.config.AndroidAppContext
import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.di.initKoin
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import kotlinx.coroutines.runBlocking
import org.koin.mp.KoinPlatform

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Context first (KSafe needs it), then Koin (idempotent on recreation)
        AndroidAppContext.init(applicationContext)
        initKoin()
        // Registers the activity-result launchers the native pickers use
        // (report save/open/share) - must run before onStart.
        FileKit.init(this)
        runBlocking {
            AppConfig.load()
            // Restore persisted login (no-op wait on Android)
            KoinPlatform.getKoin().get<AuthSession>().ready()
        }

        setContent { App() }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
