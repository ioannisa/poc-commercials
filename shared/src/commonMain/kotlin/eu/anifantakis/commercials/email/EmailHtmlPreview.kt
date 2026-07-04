package eu.anifantakis.commercials.email

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders the schedule-email HTML exactly as the customer will receive it
 * (the preview step before sending). Platform-backed:
 * - Android: android.webkit.WebView
 * - iOS: WKWebView
 * - Desktop: Swing's HTML pane (approximate - the email markup is
 *   deliberately old-school table HTML, which it handles)
 * - Browsers (js/wasmJs): an iframe overlaid on the canvas at this
 *   composable's position
 */
@Composable
expect fun EmailHtmlPreview(html: String, modifier: Modifier = Modifier)
