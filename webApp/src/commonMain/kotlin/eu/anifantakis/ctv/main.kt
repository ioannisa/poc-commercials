package eu.anifantakis.ctv

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import eu.anifantakis.ctv.auth.AuthSession
import eu.anifantakis.ctv.config.AppConfig
import eu.anifantakis.ctv.di.initKoin
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    initKoin()
    MainScope().launch {
        AppConfig.load()
        // WebCrypto decrypts the KSafe cache asynchronously - must finish
        // before the first (synchronous) read of the persisted session
        KoinPlatform.getKoin().get<AuthSession>().ready()
        ComposeViewport { App() }
    }
}
