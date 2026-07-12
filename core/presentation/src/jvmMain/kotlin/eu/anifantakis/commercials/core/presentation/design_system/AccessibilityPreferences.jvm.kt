package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.runtime.Composable

// Desktop JVM: no portable OS hook for reduce-motion/high-contrast (the JDK
// exposes neither macOS's Reduce Motion nor Windows' contrast themes).
// Best-effort constants - honestly a snapshot of "unknown", not observable.
@Composable
internal actual fun rememberAccessibilityPreferences(): AccessibilityPreferences =
    AccessibilityPreferences(reduceMotion = false, highContrast = false)
