package eu.anifantakis.commercials

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.core.presentation.commands.CommandRegistry
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationManager
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.di.initKoin
import eu.anifantakis.commercials.window.AppMenuBar
import eu.anifantakis.commercials.window.SingleInstance
import eu.anifantakis.commercials.window.WindowStateStore
import eu.anifantakis.commercials.window.rememberPersistedWindowState
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.koin.mp.KoinPlatform
import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane

/** What macOS shows in the screen menu bar / dock (packaged apps get it from `dockName`). */
private const val APP_NAME = "Commercials Manager"

/**
 * Tells the user why the second instance vanished, instead of leaving them staring
 * at a dock icon that bounced and died.
 *
 * SWING, not Compose - there is no composition here and never will be; we are about
 * to `return` out of main().
 *
 * But the STRINGS come from the app's own catalog, in the app's own language, and
 * getting that right is the whole trick. The language normally arrives from KSafe,
 * which is precisely the store this instance must not open (see
 * [SingleInstance.runningLanguage]). So the RUNNING instance publishes its language
 * to a plain sidecar file, and this one reads it: the user is greeted in the language
 * of the window that is actually on their screen.
 *
 * `localized()` - not `localizedCompose()` / `Strings[...]`, which subscribe to
 * LocalLanguage so they can RECOMPOSE on a language switch. There is no composition
 * here to recompose, and the language cannot change in the half-second this dialog
 * exists. [LocalizationManager] is a plain object, so it resolves with no Koin and no
 * KSafe - which is the only reason any of this is possible from here.
 *
 * `resolveStartup` already owns the fallback chain we want when the sidecar is missing
 * (an app that has never run since this feature landed): published choice -> OS locale
 * -> English.
 */
private fun alreadyRunningDialog() {
    LocalizationManager.setLanguage(
        LocalizationManager.resolveStartup(SingleInstance.runningLanguage())
    )
    val title = StringKey.DESKTOP_ALREADY_RUNNING_TITLE.localized()
    val message = StringKey.DESKTOP_ALREADY_RUNNING_MESSAGE.localized()

    // Headless (CI, a server box, an SSH session): there is no screen to show this on,
    // and popping a dialog would hang. The exit is the message.
    if (GraphicsEnvironment.isHeadless()) {
        System.err.println("$title: $message")
        return
    }
    JOptionPane.showMessageDialog(null, message, title, JOptionPane.INFORMATION_MESSAGE)
}

fun main() {
    // MUST be the FIRST thing in main(): macOS reads the application name once,
    // when AWT initializes. Unset, it falls back to the main CLASS name - which
    // is why an unbundled `./gradlew run` showed "MainKt" in the menu bar.
    // Harmless on Windows/Linux.
    System.setProperty("apple.awt.application.name", APP_NAME)

    // ONE instance per OS user, and this must come BEFORE initKoin(): a second
    // instance that reached KSafe would attach to the running app's session store
    // and start writing to it. It has to bounce off without touching anything.
    //
    // (macOS Finder already refuses to launch the same .app twice; Windows, Linux
    // and every terminal launch will happily start a second one.)
    if (!SingleInstance.acquire()) {
        alreadyRunningDialog()
        return
    }

    initKoin()
    // Native file dialogs (reports save panel) need the app identity once.
    FileKit.init(appId = "CommercialsManager")
    runBlocking {
        AppConfig.load()
        // Restore persisted login (no-op wait on JVM)
        KoinPlatform.getKoin().get<AuthSession>().ready()
    }

    application {
        // Window geometry survives relaunches (KSafe Plain, debounced,
        // clamped against the current screens).
        val windowStore = remember { WindowStateStore(KoinPlatform.getKoin().get()) }
        val commandRegistry = remember { KoinPlatform.getKoin().get<CommandRegistry>() }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Commercials Manager",
            state = rememberPersistedWindowState(windowStore),
        ) {
            // Real screen menu bar on macOS, in-window menu on Win/Linux;
            // items grey out from the command registry's live state.
            AppMenuBar(commandRegistry)
            var isLoading by remember { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                delay(100) // Let the window render first
                isLoading = false
            }

            // We hold the lock, so we are the one who can say what language the user
            // is looking at. Publish it - on startup and on every switch - so a second
            // instance can be turned away in that language without opening our KSafe
            // store to find out. Collects, rather than reading once: the user may
            // switch language long after launch.
            LaunchedEffect(Unit) {
                LocalizationManager.currentLanguage.collect { SingleInstance.publishLanguage(it.code) }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(isLoading, enter = fadeIn(), exit = fadeOut()) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Commercials Manager",
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Loading...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                            Spacer(Modifier.height(24.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }

                AnimatedVisibility(!isLoading, enter = fadeIn()) { App() }
            }
        }
    }
}
