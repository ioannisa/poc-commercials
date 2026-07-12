package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.window

/**
 * Read at composition from the standard media queries. v1 is a page-load
 * snapshot (a reload picks up changes): a live MediaQueryList listener
 * needs per-target actuals - the js and wasmJs event-listener bindings
 * differ - which is not worth it for a preference users rarely flip
 * mid-session in a browser.
 */
@Composable
internal actual fun rememberAccessibilityPreferences(): AccessibilityPreferences = remember {
    AccessibilityPreferences(
        reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches,
        highContrast = window.matchMedia("(prefers-contrast: more)").matches,
    )
}
