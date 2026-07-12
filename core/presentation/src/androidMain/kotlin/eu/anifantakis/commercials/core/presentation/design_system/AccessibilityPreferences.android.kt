package eu.anifantakis.commercials.core.presentation.design_system

import android.database.ContentObserver
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Reduce motion = the animator scales are zeroed ("Remove animations" in
 * accessibility settings zeroes all three; checking TRANSITION covers it).
 * High contrast = the (string-keyed, but long-stable) secure setting behind
 * "High contrast text". Both observed via ContentObserver so a mid-session
 * toggle recomposes.
 */
@Composable
internal actual fun rememberAccessibilityPreferences(): AccessibilityPreferences {
    val context = LocalContext.current
    val resolver = context.contentResolver

    fun read() = AccessibilityPreferences(
        reduceMotion = Settings.Global.getFloat(
            resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f,
        ) == 0f,
        highContrast = Settings.Secure.getInt(
            resolver, "high_text_contrast_enabled", 0,
        ) == 1,
    )

    var prefs by remember { mutableStateOf(read()) }

    DisposableEffect(resolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                prefs = read()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false, observer,
        )
        resolver.registerContentObserver(
            Settings.Secure.getUriFor("high_text_contrast_enabled"), false, observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return prefs
}
