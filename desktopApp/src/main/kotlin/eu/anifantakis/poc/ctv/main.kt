package eu.anifantakis.poc.ctv

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
import androidx.compose.material3.MaterialTheme
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
import eu.anifantakis.poc.ctv.config.AppConfig

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "POCCTV",
    ) {
        var isLoading by remember { mutableStateOf(true) }
        var loadError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            try {
                AppConfig.load()
                isLoading = false
            } catch (e: Exception) {
                loadError = e.message ?: e::class.simpleName
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // Splash screen
            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1A1A2E)),
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
                        Text(
                            text = loadError?.let { "Config error: $it" } ?: "Loading...",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            // Main app
            AnimatedVisibility(
                visible = !isLoading,
                enter = fadeIn()
            ) {
                App()
            }
        }
    }
}
