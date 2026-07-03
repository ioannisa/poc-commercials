package eu.anifantakis.poc.ctv

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import eu.anifantakis.poc.ctv.auth.AuthSession
import eu.anifantakis.poc.ctv.config.AppConfig
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        AppConfig.load()
        // WebCrypto decrypts the KSafe cache asynchronously - must finish
        // before the first (synchronous) read of the persisted session
        AuthSession.ready()
        ComposeViewport { App() }
    }
}
