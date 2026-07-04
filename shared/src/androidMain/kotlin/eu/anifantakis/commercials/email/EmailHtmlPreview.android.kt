package eu.anifantakis.commercials.email

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
actual fun EmailHtmlPreview(html: String, modifier: Modifier) {
    AndroidView(
        factory = { WebView(it) },
        update = { web ->
            // tag guards against reloading on unrelated recompositions
            if (web.tag != html) {
                web.tag = html
                web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = modifier,
    )
}
