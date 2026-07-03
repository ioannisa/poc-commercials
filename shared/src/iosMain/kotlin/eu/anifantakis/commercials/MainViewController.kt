package eu.anifantakis.commercials

import androidx.compose.ui.window.ComposeUIViewController
import eu.anifantakis.commercials.config.AppConfig
import kotlinx.coroutines.runBlocking

fun MainViewController(): platform.UIKit.UIViewController {
    runBlocking { AppConfig.load() }
    return ComposeUIViewController { App() }
}
