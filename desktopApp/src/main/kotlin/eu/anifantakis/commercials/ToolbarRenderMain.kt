package eu.anifantakis.commercials

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import eu.anifantakis.commercials.core.presentation.design_system.LegacyToolbarLab
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Dev-only OFFSCREEN render of the legacy toolbar mock to PNGs - so the design
 * can be reviewed without opening a window or logging in (the real header is
 * behind auth). Renders the light and dark variants.
 *
 * ./gradlew :desktopApp:renderToolbar
 * -> writes build/toolbar-render-light.png and build/toolbar-render-dark.png
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    render(dark = false, path = "build/toolbar-render-light.png")
    render(dark = true, path = "build/toolbar-render-dark.png")
}

@OptIn(ExperimentalComposeUiApi::class)
private fun render(dark: Boolean, path: String) {
    val image = renderComposeScene(width = 2400, height = 400, density = Density(2f)) {
        LegacyToolbarLab(dark = dark)
    }
    val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
    File(path).apply { parentFile?.mkdirs(); writeBytes(data.bytes) }
    println("WROTE ${File(path).absolutePath} (${data.bytes.size} bytes)")
}
