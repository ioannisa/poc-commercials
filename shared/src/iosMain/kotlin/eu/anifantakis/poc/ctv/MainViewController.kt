package eu.anifantakis.poc.ctv

import androidx.compose.ui.window.ComposeUIViewController
import eu.anifantakis.poc.ctv.config.ConfigGate

fun MainViewController() = ComposeUIViewController { ConfigGate { App() } }
