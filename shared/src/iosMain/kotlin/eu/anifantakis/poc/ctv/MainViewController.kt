package eu.anifantakis.poc.ctv

import androidx.compose.ui.window.ComposeUIViewController
import eu.anifantakis.poc.ctv.config.AppConfig
import kotlinx.coroutines.runBlocking

fun MainViewController(): platform.UIKit.UIViewController {
    runBlocking { AppConfig.load() }
    return ComposeUIViewController { App() }
}
