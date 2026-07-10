package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The application's Material 3 theme (named after the brand, dealer-totem
 * convention - the `AppTheme` NAME belongs to the typography accessor
 * object). Compose Multiplatform draws every widget itself (Skia) rather
 * than delegating to native OS controls, so "matching the OS" here means a
 * deliberate, cohesive palette plus honouring the platform's light/dark
 * preference - [isSystemInDarkTheme] reads the real macOS/Windows setting on
 * desktop and the browser's `prefers-color-scheme` on web/wasm.
 *
 * Typography: the bundled Roboto family (identical rendering on every
 * platform, full Greek coverage) built at the user's [FontSizeStep] - the
 * whole type system scales from the preferences slider.
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

// Slightly softer corners than the Material default read as more "app", less "demo".
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CommercialsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontSizeStep: FontSizeStep = FontSizeStep.DEFAULT,
    content: @Composable () -> Unit,
) {
    val appTypography = buildAppTypography(
        roboto = robotoFamily(),
        robotoMono = robotoMonoFamily(),
        step = fontSizeStep,
    )
    CompositionLocalProvider(
        LocalAppTypography provides appTypography,
        LocalFontSizeStep provides fontSizeStep,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) BrandDark else BrandLight,
            shapes = AppShapes,
            typography = appTypography.material,
            content = content,
        )
    }
}
