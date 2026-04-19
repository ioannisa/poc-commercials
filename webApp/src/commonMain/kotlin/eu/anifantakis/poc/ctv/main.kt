package eu.anifantakis.poc.ctv

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import eu.anifantakis.poc.ctv.config.ConfigGate

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        ConfigGate { App() }
    }
}
