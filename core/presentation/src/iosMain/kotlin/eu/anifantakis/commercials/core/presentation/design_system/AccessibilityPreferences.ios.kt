package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityDarkerSystemColorsEnabled
import platform.UIKit.UIAccessibilityDarkerSystemColorsStatusDidChangeNotification
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification

/**
 * Reduce Motion and Increase Contrast, live: users toggle these while apps
 * run, and UIKit posts a notification for each - subscribing is the whole
 * reason this accessor is a composable and not a constant.
 */
@Composable
internal actual fun rememberAccessibilityPreferences(): AccessibilityPreferences {
    fun read() = AccessibilityPreferences(
        reduceMotion = UIAccessibilityIsReduceMotionEnabled(),
        highContrast = UIAccessibilityDarkerSystemColorsEnabled(),
    )

    var prefs by remember { mutableStateOf(read()) }

    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        val observers = listOf(
            UIAccessibilityReduceMotionStatusDidChangeNotification,
            UIAccessibilityDarkerSystemColorsStatusDidChangeNotification,
        ).map { name ->
            center.addObserverForName(name, `object` = null, queue = queue) { _ ->
                prefs = read()
            }
        }
        onDispose { observers.forEach(center::removeObserver) }
    }

    return prefs
}
