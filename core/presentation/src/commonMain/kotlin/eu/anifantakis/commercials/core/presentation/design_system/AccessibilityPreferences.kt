package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * The OS-level accessibility preferences the design system honours.
 *
 * `reduceTransparency` is deliberately ABSENT: the app uses no blur/acrylic/
 * translucent surfaces, so it would be a flag no component reads. Add it if
 * that ever changes.
 *
 * High-contrast policy (what `highContrast = true` means for components):
 * thicker borders, no near-invisible disabled states, stronger focus
 * indication, and never conveying information by colour alone - which has
 * teeth in the scheduler, where programme colours are data.
 */
@Immutable
data class AccessibilityPreferences(
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false,
)

/**
 * OBSERVABLE where the platform allows it - Reduce Motion is toggled while
 * apps run; that is the point of the setting. iOS/Android actuals subscribe
 * to the system notification; web reads the media queries at composition
 * (page reload picks up changes); desktop JVM is best-effort constants.
 */
@Composable
internal expect fun rememberAccessibilityPreferences(): AccessibilityPreferences

/** Safe default so standalone previews render; CommercialsTheme always provides. */
val LocalAccessibilityPreferences = compositionLocalOf { AccessibilityPreferences() }

/**
 * The ONLY sanctioned way to turn a motion token into an animation spec.
 * `snap()` rather than `tween(0)`: it skips the animation system entirely.
 */
fun <T> MotionTokens.fastSpec(a11y: AccessibilityPreferences): FiniteAnimationSpec<T> =
    if (a11y.reduceMotion) snap() else tween(fastMillis)

fun <T> MotionTokens.mediumSpec(a11y: AccessibilityPreferences): FiniteAnimationSpec<T> =
    if (a11y.reduceMotion) snap() else tween(mediumMillis)

fun <T> MotionTokens.slowSpec(a11y: AccessibilityPreferences): FiniteAnimationSpec<T> =
    if (a11y.reduceMotion) snap() else tween(slowMillis)
