package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import eu.anifantakis.commercials.core.presentation.design_system.platform.InputCapabilities
import eu.anifantakis.commercials.core.presentation.design_system.platform.UiPlatform
import eu.anifantakis.commercials.core.presentation.design_system.platform.detectUiPlatform
import eu.anifantakis.commercials.core.presentation.design_system.platform.startupInputCapabilities

/**
 * The application's Material 3 theme (named after the brand, dealer-totem
 * convention - the `AppTheme` NAME belongs to the accessor object below).
 *
 * Compose draws every widget itself (Skia) rather than delegating to native
 * OS controls, so this is a PLATFORM-ADAPTED theme, not a native one: the
 * visual language (geometry, shape, depth, motion) follows the OS via
 * [PlatformVisualTokens]; interaction sizing follows the hardware + the
 * user's density preference via [InteractionMetrics]; and the palette
 * honours the platform's light/dark preference.
 *
 * The derivation happens HERE, before the providers: raw platform and raw
 * input capabilities never enter the ambient scope - components read only
 * the four `AppTheme` accessors.
 */

private val BrandLight = lightColorScheme(
    primary = Color(0xFF1B5FA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E3FF),
    onPrimaryContainer = Color(0xFF001C3A),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F8),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3DAFF),
    onTertiaryContainer = Color(0xFF251431),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF42474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF004883),
    onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFFD7BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF3DAFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF42474E),
)

/**
 * Production entry point. Deliberately NO platform argument: the runtime
 * platform is detected once, internally. Previews and the showcase use the
 * internal overload with a [PlatformPreviewProfile].
 */
@Composable
fun CommercialsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontSizeStep: FontSizeStep = FontSizeStep.DEFAULT,
    densityPreference: DensityPreference = DensityPreference.DEFAULT,
    content: @Composable () -> Unit,
) {
    val platform = remember { detectUiPlatform() }
    val input = remember { startupInputCapabilities() }
    CommercialsThemeImpl(
        platform = platform,
        input = input,
        darkTheme = darkTheme,
        fontSizeStep = fontSizeStep,
        densityPreference = densityPreference,
        a11yOverride = null,
        content = content,
    )
}

/**
 * Public preview-only mirror of the internal platform enum, so tooling
 * (PlatformShowcase, previews, screenshot tests) can render any OS look on
 * any machine without the internal type leaking into a public signature.
 */
enum class PlatformPreviewProfile {
    ANDROID, IOS, MACOS, WINDOWS, LINUX, WEB;

    internal fun toUiPlatform(): UiPlatform = when (this) {
        ANDROID -> UiPlatform.ANDROID
        IOS -> UiPlatform.IOS
        MACOS -> UiPlatform.MACOS
        WINDOWS -> UiPlatform.WINDOWS
        LINUX -> UiPlatform.LINUX
        WEB -> UiPlatform.WEB
    }
}

/**
 * Showcase/preview overload: pin the profile, simulate the hardware and the
 * a11y environment. Internal - production code has no business choosing a
 * platform.
 */
@Composable
internal fun CommercialsTheme(
    profile: PlatformPreviewProfile,
    input: InputCapabilities,
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontSizeStep: FontSizeStep = FontSizeStep.DEFAULT,
    densityPreference: DensityPreference = DensityPreference.DEFAULT,
    a11yOverride: AccessibilityPreferences? = null,
    content: @Composable () -> Unit,
) = CommercialsThemeImpl(
    platform = profile.toUiPlatform(),
    input = input,
    darkTheme = darkTheme,
    fontSizeStep = fontSizeStep,
    densityPreference = densityPreference,
    a11yOverride = a11yOverride,
    content = content,
)

@Composable
private fun CommercialsThemeImpl(
    platform: UiPlatform,
    input: InputCapabilities,
    darkTheme: Boolean,
    fontSizeStep: FontSizeStep,
    densityPreference: DensityPreference,
    a11yOverride: AccessibilityPreferences?,
    content: @Composable () -> Unit,
) {
    // Derive BEFORE providing: AUTO is resolved here, once, statically -
    // and raw capabilities never become ambient.
    val visual = remember(platform) { platformVisualTokens(platform) }
    val effectiveDensity = remember(densityPreference, input) {
        resolveDensity(densityPreference, input)
    }
    val interaction = remember(effectiveDensity, input) {
        deriveInteractionMetrics(input, effectiveDensity)
    }
    val a11y = a11yOverride ?: rememberAccessibilityPreferences()

    // REMEMBERED: this builds ~20 TextStyles, and its result feeds a STATIC
    // CompositionLocal - rebuilding it on every theme-scope recomposition both
    // reallocates the lot and forces the provider to structurally compare the
    // whole tree of styles to discover nothing changed. The font families have
    // structural equality (their Fonts are cached by compose-resources), so
    // these keys hit whenever the inputs are genuinely the same.
    val roboto = robotoFamily()
    val robotoMono = robotoMonoFamily()
    val appTypography = remember(roboto, robotoMono, fontSizeStep, visual.minTextWeight) {
        buildAppTypography(
            roboto = roboto,
            robotoMono = robotoMono,
            step = fontSizeStep,
            minWeight = visual.minTextWeight,
        )
    }

    // GLYPH FALLBACK. Roboto has no Hebrew - and no Chinese, and no Arabic. This
    // is what lets a face that DOES have them draw those characters, without any
    // screen knowing it happened. FontFallback.kt spells out why Compose cannot
    // be asked to do it for us on Skia, and what was tried first.
    val glyphFallback = rememberGlyphFallback(fallbackFontFamilies())

    CompositionLocalProvider(
        LocalGlyphFallback provides glyphFallback,
        LocalAppTypography provides appTypography,
        LocalFontSizeStep provides fontSizeStep,
        LocalPlatformVisualTokens provides visual,
        LocalInteractionMetrics provides interaction,
        LocalAccessibilityPreferences provides a11y,
        // The hit-area floor for every M3 control that consumes it
        // (IconButton, Checkbox, RadioButton, Switch, Slider, text fields).
        // Fed by the derived POLICY - never by the OS, never by raw capability.
        LocalMinimumInteractiveComponentSize provides interaction.minimumTargetSize,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) BrandDark else BrandLight,
            shapes = visual.toShapes(),
            typography = appTypography.material,
            content = content,
        )
    }
}

/**
 * Clean access to the design-system ambients, mimicking `MaterialTheme.*`
 * (dealer-totem convention). This is the ENTIRE public ambient surface -
 * exactly four design-system values plus the pre-existing typography pair.
 */
object AppTheme {
    val typography: AppTypography
        @Composable
        get() = LocalAppTypography.current

    val fontSizeStep: FontSizeStep
        @Composable
        get() = LocalFontSizeStep.current

    /** What things look like on this OS (geometry, shape, depth, motion). */
    val visualTokens: PlatformVisualTokens
        @Composable
        get() = LocalPlatformVisualTokens.current

    /** How things respond to input (target sizes, hover, touch gestures). */
    val interaction: InteractionMetrics
        @Composable
        get() = LocalInteractionMetrics.current

    /** Reduced motion / high contrast - honour these in every animation. */
    val a11y: AccessibilityPreferences
        @Composable
        get() = LocalAccessibilityPreferences.current

    /** Window size class - dialogs/forms/chrome adapt; grids never read it. */
    val window: WindowSize
        @Composable
        get() = LocalWindowSize.current
}
