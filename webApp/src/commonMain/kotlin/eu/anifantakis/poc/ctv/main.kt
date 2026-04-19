package eu.anifantakis.poc.ctv

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import eu.anifantakis.poc.ctv.config.AppConfig
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        AppConfig.load()
        ComposeViewport { App() }
    }
}
