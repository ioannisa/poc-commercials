package eu.anifantakis.commercials

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eu.anifantakis.commercials.core.presentation.design_system.PlatformShowcase

/**
 * Dev-only entry: the design-system laboratory.
 * `./gradlew :desktopApp:runShowcase`
 *
 * No Koin, no config, no server - the showcase simulates its whole
 * environment (platform profile, input hardware, density, a11y, RTL,
 * font step, window class) from the control rail.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "PlatformShowcase - design-system lab",
        state = rememberWindowState(width = 1280.dp, height = 900.dp),
    ) {
        PlatformShowcase()
    }
}
