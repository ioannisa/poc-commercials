package eu.anifantakis.commercials.feature.schedule_email.presentation.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import kotlinx.browser.document
import org.w3c.dom.HTMLIFrameElement

/**
 * ONE actual for js AND wasmJs (compose.ui's HtmlElementView lives in its
 * shared webMain, as does this). Replaces the hand-rolled
 * `position: fixed; z-index: 9999` overlay that floated above every Compose
 * dialog and never clipped to a scrolling parent - HtmlElementView hands
 * placement, clipping, z-order and disposal to Compose.
 *
 * Layering/clipping behaviour verified at runtime, not assumed: an HTML
 * element still renders in the DOM over the canvas; Compose coordinates it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun EmailHtmlPreview(html: String, modifier: Modifier) {
    HtmlElementView(
        factory = {
            (document.createElement("iframe") as HTMLIFrameElement).apply {
                style.border = "0"
                style.background = "#FFFFFF"
                style.width = "100%"
                style.height = "100%"
            }
        },
        modifier = modifier,
        update = { iframe -> iframe.srcdoc = html },
    )
}
