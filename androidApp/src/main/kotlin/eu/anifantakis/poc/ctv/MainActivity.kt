package eu.anifantakis.poc.ctv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import eu.anifantakis.poc.ctv.auth.AuthSession
import eu.anifantakis.poc.ctv.config.AndroidAppContext
import eu.anifantakis.poc.ctv.config.AppConfig
import eu.anifantakis.poc.ctv.di.initKoin
import kotlinx.coroutines.runBlocking
import org.koin.mp.KoinPlatform

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Context first (KSafe needs it), then Koin (idempotent on recreation)
        AndroidAppContext.init(applicationContext)
        initKoin()
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
