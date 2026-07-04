package eu.anifantakis.commercials.feature.schedule_email.presentation.webview

import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.scene.web.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Canary for the OpenJFX wiring in shared/build.gradle.kts: the classifier
 * resolution must pull REAL platform jars (the unclassified OpenJFX jars are
 * empty), and WebKit must parse report-sized table HTML quickly - the whole
 * reason JavaFX replaced Swing's HTML pane for the email preview.
 */
class JavaFxWebViewSmokeTest {

    @Test
    fun webkitLoadsReportSizedTableHtml() {
        // ~1MB of table markup, the shape renderScheduleEmail produces
        val html = buildString {
            append("<html><body>")
            repeat(6) {
                append("<table border='1' cellspacing='0'>")
                repeat(100) {
                    append("<tr>")
                    repeat(31) {
                        append("<td style='background:#EBF3FB;font-size:10px;padding:2px'>3</td>")
                    }
                    append("</tr>")
                }
                append("</table>")
            }
            append("</body></html>")
        }

        val started = CountDownLatch(1)
        Platform.startup { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS), "JavaFX toolkit failed to start")
        Platform.setImplicitExit(false)

        val loaded = CountDownLatch(1)
        var finalState: Worker.State? = null
        val t0 = System.currentTimeMillis()
        Platform.runLater {
            val web = WebView()
            web.engine.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.SUCCEEDED || state == Worker.State.FAILED) {
                    finalState = state
                    loaded.countDown()
                }
            }
            web.engine.loadContent(html)
        }
        assertTrue(loaded.await(30, TimeUnit.SECONDS), "WebKit did not finish loading")
        assertTrue(finalState == Worker.State.SUCCEEDED, "WebKit load ended in $finalState")
        println("WebView parsed ${html.length / 1024}KB in ${System.currentTimeMillis() - t0}ms")
    }
}
