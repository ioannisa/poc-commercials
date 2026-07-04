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
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.di.initKoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.koin.mp.KoinPlatform

fun main() {
    initKoin()
    runBlocking {
        AppConfig.load()
        // Restore persisted login (no-op wait on JVM)
        KoinPlatform.getKoin().get<AuthSession>().ready()
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Commercials Manager",
            // Occupy the whole available screen area (respects the OS menu
            // bar/dock/taskbar - unlike fullscreen, the window chrome stays).
            state = rememberWindowState(placement = WindowPlacement.Maximized),
        ) {
            var isLoading by remember { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                delay(100) // Let the window render first
                isLoading = false
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
