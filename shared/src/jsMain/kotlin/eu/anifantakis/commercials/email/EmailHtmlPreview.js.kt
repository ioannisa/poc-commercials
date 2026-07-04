package eu.anifantakis.commercials.email

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlinx.browser.document
import org.w3c.dom.HTMLIFrameElement

/**
 * Compose-web draws to a canvas, so the "webview" is a real iframe overlaid
 * on the page at this composable's position (tracked via
 * onGloballyPositioned; window px -> CSS px via density).
 */
@Composable
actual fun EmailHtmlPreview(html: String, modifier: Modifier) {
    val iframe = remember {
        (document.createElement("iframe") as HTMLIFrameElement).apply {
            style.position = "fixed"
            style.border = "0"
            style.background = "#FFFFFF"
            style.zIndex = "9999"
            document.body?.appendChild(this)
        }
    }
    DisposableEffect(Unit) {
        onDispose { iframe.parentNode?.removeChild(iframe) }
    }
    LaunchedEffect(html) { iframe.srcdoc = html }

    val density = LocalDensity.current.density
    Box(
        modifier.onGloballyPositioned { coords ->
            val b = coords.boundsInWindow()
            iframe.style.left = "${b.left / density}px"
            iframe.style.top = "${b.top / density}px"
            iframe.style.width = "${b.width / density}px"
            iframe.style.height = "${b.height / density}px"
        }
    )
}
