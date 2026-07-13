package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.platform.UiPlatform

/**
 * Keyboard-focus indication STYLE. Whether a ring is SHOWN is never decided
 * here (and never by platform): visibility comes from real focus state plus
 * the runtime input mode - an iPad with a keyboard is a keyboard device.
 */
@Immutable
data class FocusRingStyle(
    val width: Dp,
    val gap: Dp,
)

/**
 * Natural animation durations for the platform. Consume through the
 * a11y-aware helpers below - never `tween(motion.mediumMillis)` directly in
 * a component, or reduced-motion silently stops working.
 */
@Immutable
data class MotionTokens(
    val fastMillis: Int,
    val mediumMillis: Int,
    val slowMillis: Int,
)

/**
 * WHAT the OS wants things to LOOK like - control geometry, shape, depth,
 * density and motion. Keyed by [UiPlatform]; never changes after startup.
 *
 * NOT in here, deliberately:
 * - spacing (platform-neutral) -> [UIConst]
 * - interactive/hit sizing (hardware + user policy) -> [InteractionMetrics]
 * - colors -> `MaterialTheme.colorScheme` (the single color door)
 *
 * Read via `AppTheme.visualTokens`.
 */
@Immutable
data class PlatformVisualTokens(
    // controls
    val buttonHeight: Dp,
    val buttonHeightDense: Dp,
    /**
     * SPIKE (PR 1): M3 `OutlinedTextFieldDefaults.MinHeight` is 56.dp; a
     * `heightIn(min=)` override wins, but content padding then binds. The
     * desktop/web value below is a SAFE provisional (48.dp) - whether it can
     * drop to ~40.dp without wrecking the floating label is decided in
     * PlatformShowcase, not committed here.
     */
    val fieldHeight: Dp,
    val menuRowHeight: Dp,
    val controlBorderWidth: Dp,
    val buttonPaddingHorizontal: Dp,
    val buttonPaddingVertical: Dp,
    val fieldContentPadding: PaddingValues,
    // icons
    val iconSmall: Dp,
    val iconMedium: Dp,
    val iconLarge: Dp,
    // shape
    val cornerSmall: Dp,
    val cornerMedium: Dp,
    val cornerLarge: Dp,
    val cornerExtraLarge: Dp,
    // depth: elevation XOR border - the platform tell
    val cardElevation: Dp,
    val cardBorderWidth: Dp,
    val buttonElevation: Dp,
    val dialogTonalElevation: Dp,
    // containers
    val screenPadding: Dp,
    val cardPadding: Dp,
    val listItemPaddingVertical: Dp,
    val formMaxWidth: Dp,
    val dialogMinWidth: Dp,
    val dialogMaxWidth: Dp,
    // focus + motion
    val focusRing: FocusRingStyle,
    val motion: MotionTokens,
    // scrollbars (reach the grids by injection - GridMetrics)
    val scrollbarThickness: Dp,
    val scrollbarAlwaysVisible: Boolean,
    /**
     * The LIGHTEST weight this platform may render body text at.
     *
     * Skia rasterises text without hinting or stem-darkening in the BROWSER,
     * while the native platforms lean on the OS to thicken small stems. Identical
     * font, identical size - and the web comes out visibly thinner. It is not a
     * font bug (the faces carry the same usWeightClass); it is how the glyphs are
     * rasterised, and no font file fixes it.
     *
     * So the web asks for one step more weight, and only where it is thin: this
     * is a FLOOR, not an offset. Regular (400) becomes Medium (500) - a face we
     * actually ship - while Medium and Bold are already heavy enough and stay put.
     * An offset would have pushed Medium to 600, which we have no face for, and
     * Compose would have resolved it up to Bold.
     */
    val minTextWeight: FontWeight,
)

internal fun PlatformVisualTokens.toShapes() = Shapes(
    extraSmall = RoundedCornerShape(cornerSmall / 2),
    small = RoundedCornerShape(cornerSmall),
    medium = RoundedCornerShape(cornerMedium),
    large = RoundedCornerShape(cornerLarge),
    extraLarge = RoundedCornerShape(cornerExtraLarge),
)

val LocalPlatformVisualTokens = staticCompositionLocalOf<PlatformVisualTokens> {
    error("No PlatformVisualTokens provided - wrap the content in CommercialsTheme.")
}

/**
 * THE one platform `when` in the codebase (ArchitectureTest-enforced). If
 * you feel the urge to write `when (platform)` anywhere else, you want a
 * new TOKEN here, not a branch there.
 */
internal fun platformVisualTokens(platform: UiPlatform): PlatformVisualTokens = when (platform) {
    // M3 as designed: expressive corners, real elevation, 48dp controls.
    UiPlatform.ANDROID -> PlatformVisualTokens(
        buttonHeight = 48.dp, buttonHeightDense = 40.dp,
        fieldHeight = 56.dp, menuRowHeight = 48.dp,
        controlBorderWidth = 1.dp,
        buttonPaddingHorizontal = 24.dp, buttonPaddingVertical = 12.dp,
        fieldContentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        iconSmall = 20.dp, iconMedium = 24.dp, iconLarge = 28.dp,
        cornerSmall = 8.dp, cornerMedium = 12.dp, cornerLarge = 16.dp, cornerExtraLarge = 28.dp,
        cardElevation = 1.dp, cardBorderWidth = 0.dp,
        buttonElevation = 1.dp, dialogTonalElevation = 6.dp,
        screenPadding = 16.dp, cardPadding = 16.dp, listItemPaddingVertical = 12.dp,
        formMaxWidth = 480.dp, dialogMinWidth = 280.dp, dialogMaxWidth = 560.dp,
        focusRing = FocusRingStyle(width = 2.dp, gap = 1.dp),
        motion = MotionTokens(fastMillis = 100, mediumMillis = 250, slowMillis = 400),
        scrollbarThickness = 0.dp, scrollbarAlwaysVisible = false,
        minTextWeight = FontWeight.Normal,
    )
    // HIG-flavoured: 44pt controls, continuous-feel corners, hairlines, flat.
    UiPlatform.IOS -> PlatformVisualTokens(
        buttonHeight = 44.dp, buttonHeightDense = 36.dp,
        fieldHeight = 48.dp, menuRowHeight = 44.dp,
        controlBorderWidth = 0.5.dp,
        buttonPaddingHorizontal = 20.dp, buttonPaddingVertical = 12.dp,
        fieldContentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        iconSmall = 20.dp, iconMedium = 24.dp, iconLarge = 28.dp,
        cornerSmall = 10.dp, cornerMedium = 14.dp, cornerLarge = 18.dp, cornerExtraLarge = 20.dp,
        cardElevation = 0.dp, cardBorderWidth = 0.5.dp,
        buttonElevation = 0.dp, dialogTonalElevation = 0.dp,
        screenPadding = 16.dp, cardPadding = 16.dp, listItemPaddingVertical = 12.dp,
        formMaxWidth = 480.dp, dialogMinWidth = 280.dp, dialogMaxWidth = 540.dp,
        focusRing = FocusRingStyle(width = 2.dp, gap = 1.dp),
        motion = MotionTokens(fastMillis = 120, mediumMillis = 300, slowMillis = 450),
        scrollbarThickness = 0.dp, scrollbarAlwaysVisible = false,
        minTextWeight = FontWeight.Normal,
    )
    // AppKit-flavoured: short wide buttons, subtle corners, thick accent
    // focus ring, snappy motion, overlay scrollbars.
    UiPlatform.MACOS -> PlatformVisualTokens(
        buttonHeight = 28.dp, buttonHeightDense = 24.dp,
        fieldHeight = 48.dp /* SPIKE: target ~40 */, menuRowHeight = 24.dp,
        controlBorderWidth = 0.5.dp,
        buttonPaddingHorizontal = 14.dp, buttonPaddingVertical = 6.dp,
        fieldContentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        iconSmall = 14.dp, iconMedium = 16.dp, iconLarge = 20.dp,
        cornerSmall = 5.dp, cornerMedium = 8.dp, cornerLarge = 10.dp, cornerExtraLarge = 12.dp,
        cardElevation = 1.dp, cardBorderWidth = 0.5.dp,
        buttonElevation = 0.dp, dialogTonalElevation = 3.dp,
        screenPadding = 20.dp, cardPadding = 12.dp, listItemPaddingVertical = 6.dp,
        formMaxWidth = 560.dp, dialogMinWidth = 360.dp, dialogMaxWidth = 640.dp,
        focusRing = FocusRingStyle(width = 3.dp, gap = 2.dp),
        motion = MotionTokens(fastMillis = 80, mediumMillis = 150, slowMillis = 220),
        scrollbarThickness = 8.dp, scrollbarAlwaysVisible = false,
        minTextWeight = FontWeight.Normal,
    )
    // Fluent-flavoured: 4dp corners, 1px strokes, near-instant motion,
    // persistent scrollbar gutters.
    UiPlatform.WINDOWS -> PlatformVisualTokens(
        buttonHeight = 32.dp, buttonHeightDense = 28.dp,
        fieldHeight = 48.dp /* SPIKE: target ~40 */, menuRowHeight = 28.dp,
        controlBorderWidth = 1.dp,
        buttonPaddingHorizontal = 12.dp, buttonPaddingVertical = 6.dp,
        fieldContentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        iconSmall = 16.dp, iconMedium = 18.dp, iconLarge = 22.dp,
        cornerSmall = 4.dp, cornerMedium = 4.dp, cornerLarge = 8.dp, cornerExtraLarge = 8.dp,
        cardElevation = 0.dp, cardBorderWidth = 1.dp,
        buttonElevation = 0.dp, dialogTonalElevation = 0.dp,
        screenPadding = 16.dp, cardPadding = 12.dp, listItemPaddingVertical = 6.dp,
        formMaxWidth = 560.dp, dialogMinWidth = 360.dp, dialogMaxWidth = 640.dp,
        focusRing = FocusRingStyle(width = 2.dp, gap = 1.dp),
        motion = MotionTokens(fastMillis = 60, mediumMillis = 120, slowMillis = 180),
        scrollbarThickness = 12.dp, scrollbarAlwaysVisible = true,
        minTextWeight = FontWeight.Normal,
    )
    // libadwaita-flavoured: 6dp corners, 1px strokes, GTK density.
    UiPlatform.LINUX -> PlatformVisualTokens(
        buttonHeight = 34.dp, buttonHeightDense = 28.dp,
        fieldHeight = 48.dp /* SPIKE: target ~40 */, menuRowHeight = 28.dp,
        controlBorderWidth = 1.dp,
        buttonPaddingHorizontal = 16.dp, buttonPaddingVertical = 8.dp,
        fieldContentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        iconSmall = 16.dp, iconMedium = 18.dp, iconLarge = 22.dp,
        cornerSmall = 6.dp, cornerMedium = 8.dp, cornerLarge = 12.dp, cornerExtraLarge = 14.dp,
        cardElevation = 0.dp, cardBorderWidth = 1.dp,
        buttonElevation = 0.dp, dialogTonalElevation = 0.dp,
        screenPadding = 16.dp, cardPadding = 12.dp, listItemPaddingVertical = 6.dp,
        formMaxWidth = 560.dp, dialogMinWidth = 360.dp, dialogMaxWidth = 640.dp,
        focusRing = FocusRingStyle(width = 2.dp, gap = 1.dp),
        motion = MotionTokens(fastMillis = 80, mediumMillis = 150, slowMillis = 220),
        scrollbarThickness = 12.dp, scrollbarAlwaysVisible = true,
        minTextWeight = FontWeight.Normal,
    )
    // A good web app, not an OS imitation: roomier page padding, visible
    // scrollbars, neutral geometry.
    UiPlatform.WEB -> PlatformVisualTokens(
        buttonHeight = 32.dp, buttonHeightDense = 28.dp,
        fieldHeight = 48.dp /* SPIKE: target ~40 */, menuRowHeight = 28.dp,
        controlBorderWidth = 1.dp,
        buttonPaddingHorizontal = 16.dp, buttonPaddingVertical = 8.dp,
        fieldContentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        iconSmall = 16.dp, iconMedium = 18.dp, iconLarge = 22.dp,
        cornerSmall = 6.dp, cornerMedium = 8.dp, cornerLarge = 12.dp, cornerExtraLarge = 14.dp,
        cardElevation = 0.dp, cardBorderWidth = 1.dp,
        buttonElevation = 0.dp, dialogTonalElevation = 2.dp,
        screenPadding = 24.dp, cardPadding = 16.dp, listItemPaddingVertical = 8.dp,
        formMaxWidth = 640.dp, dialogMinWidth = 360.dp, dialogMaxWidth = 680.dp,
        focusRing = FocusRingStyle(width = 2.dp, gap = 1.dp),
        motion = MotionTokens(fastMillis = 80, mediumMillis = 150, slowMillis = 220),
        scrollbarThickness = 10.dp, scrollbarAlwaysVisible = true,
        minTextWeight = FontWeight.Medium,
    )
}
