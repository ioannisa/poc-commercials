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

private var nextFrameId = 0

/**
 * Compose-web draws to a canvas, so the "webview" is a real iframe overlaid
 * on the page at this composable's position. DOM access goes through @JsFun
 * externals (the wasm pattern used by reports-client's BrowserPdfHelper).
 */
@Composable
actual fun EmailHtmlPreview(html: String, modifier: Modifier) {
    val frameId = remember { "email-preview-${nextFrameId++}" }
    DisposableEffect(frameId) {
        createPreviewFrame(frameId)
        onDispose { removePreviewFrame(frameId) }
    }
    LaunchedEffect(html) { setPreviewFrameHtml(frameId, html) }

    val density = LocalDensity.current.density
    Box(
        modifier.onGloballyPositioned { coords ->
            val b = coords.boundsInWindow()
            positionPreviewFrame(
                frameId,
                (b.left / density).toDouble(),
                (b.top / density).toDouble(),
                (b.width / density).toDouble(),
                (b.height / density).toDouble(),
            )
        }
    )
}

@JsFun(
    """
(id) => {
    const f = document.createElement('iframe');
    f.id = id;
    f.style.position = 'fixed';
    f.style.border = '0';
    f.style.background = '#FFFFFF';
    f.style.zIndex = '9999';
    document.body.appendChild(f);
}
"""
)
private external fun createPreviewFrame(id: String)

@JsFun("""(id) => { const f = document.getElementById(id); if (f) f.remove(); }""")
private external fun removePreviewFrame(id: String)

@JsFun("""(id, html) => { const f = document.getElementById(id); if (f) f.srcdoc = html; }""")
private external fun setPreviewFrameHtml(id: String, html: String)

@JsFun(
    """
(id, left, top, width, height) => {
    const f = document.getElementById(id);
    if (!f) return;
    f.style.left = left + 'px';
    f.style.top = top + 'px';
    f.style.width = width + 'px';
    f.style.height = height + 'px';
}
"""
)
private external fun positionPreviewFrame(id: String, left: Double, top: Double, width: Double, height: Double)
