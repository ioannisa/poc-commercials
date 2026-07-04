package eu.anifantakis.commercials.feature.schedule_email.presentation.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView

/**
 * JavaFX WebView (WebKit) inside a JFXPanel. Swing's own HTML pane was
 * tried first and choked on the report's size (~1.5MB of table HTML:
 * seconds to parse, ~1fps scrolling) - the email preview needs a real
 * browser engine.
 */
@Composable
actual fun EmailHtmlPreview(html: String, modifier: Modifier) {
    SwingPanel(
        factory = {
            // Instantiating JFXPanel (on the EDT) bootstraps the FX runtime.
            JFXPanel().also { panel ->
                panel.putClientProperty("email.html", html)
                // Keep the FX thread alive after the preview closes, or a
                // second preview in the same session would find it dead.
                Platform.setImplicitExit(false)
                Platform.runLater {
                    val web = WebView()
                    web.engine.loadContent(html)
                    panel.scene = Scene(web)
                }
            }
        },
        update = { panel ->
            if (panel.getClientProperty("email.html") != html) {
                panel.putClientProperty("email.html", html)
                Platform.runLater {
                    (panel.scene?.root as? WebView)?.engine?.loadContent(html)
                }
            }
        },
        modifier = modifier,
    )
}
