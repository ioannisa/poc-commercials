package eu.anifantakis.commercials.email

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun EmailHtmlPreview(html: String, modifier: Modifier) {
    UIKitView(
        factory = {
            WKWebView(frame = CGRectZero.readValue(), configuration = WKWebViewConfiguration())
        },
        update = { web -> web.loadHTMLString(html, baseURL = null) },
        modifier = modifier,
    )
}
