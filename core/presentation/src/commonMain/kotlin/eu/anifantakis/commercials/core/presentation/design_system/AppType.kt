package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import commercials_manager.core.presentation.generated.resources.Res
import commercials_manager.core.presentation.generated.resources.roboto_bold
import commercials_manager.core.presentation.generated.resources.roboto_medium
import commercials_manager.core.presentation.generated.resources.roboto_mono_regular
import commercials_manager.core.presentation.generated.resources.roboto_regular
import org.jetbrains.compose.resources.Font

/**
 * The user's text-size preference: five discrete steps applied to EVERY
 * style the theme provides. Persisted by the preferences feature as
 * [ordinal]; MEDIUM (factor 1.0) is calibrated to look exactly like the app
 * did before the type system existed.
 */
enum class FontSizeStep(val factor: Float) {
    XSMALL(0.85f),
    SMALL(0.925f),
    MEDIUM(1.0f),
    LARGE(1.075f),
    XLARGE(1.15f);

    companion object {
        val DEFAULT = MEDIUM
        fun fromOrdinal(value: Int): FontSizeStep = entries.getOrElse(value) { DEFAULT }
    }
}

/**
 * The app's typography: the Material scale PLUS the app-specific roles the
 * screens actually use (same pattern as the dealer-totem reference project).
 * Screens never hardcode sizes - they pick a role through
 * [AppText][eu.anifantakis.commercials.core.presentation.design_system.components.AppText],
 * so the font-size preference reaches every text in the app.
 */
data class AppTypography(
    val material: Typography,

    /** Screen header next to the back arrow (data migration, preferences). */
    val screenTitle: TextStyle,
    /** Card/section headers (step headers, dialog sections). */
    val sectionTitle: TextStyle,
    /** Big numbers in stat headers (Σύνολο Spots / Συνολική Διάρκεια). */
    val statValue: TextStyle,
    /** The small label above/beside a stat value. */
    val statLabel: TextStyle,
    /** Console/log lines (migration progress) - monospaced. */
    val logLine: TextStyle,
    /** Monospaced emphasis (recovery codes, identifiers). */
    val mono: TextStyle,
)

val LocalAppTypography = staticCompositionLocalOf<AppTypography> {
    error("No AppTypography provided - wrap the content in CommercialsTheme.")
}

/**
 * The active [FontSizeStep] itself, for the few callers that need the raw
 * FACTOR rather than a ready-made [TextStyle]: the standalone grid toolkits
 * are leaf modules that cannot see [AppTypography], so the screen hands them
 * `AppTheme.fontSizeStep.factor` as their `scale` (they size their own dense
 * type and cell geometry from it).
 */
val LocalFontSizeStep = staticCompositionLocalOf { FontSizeStep.DEFAULT }

// The `AppTheme` accessor object lives in AppTheme.kt (with the theme
// composable and the rest of the ambient accessors) - the filename says
// where to find it.

/** The bundled Roboto family (full Greek coverage on every platform). */
@Composable
internal fun robotoFamily(): FontFamily = FontFamily(
    Font(Res.font.roboto_regular, FontWeight.Normal),
    Font(Res.font.roboto_medium, FontWeight.Medium),
    Font(Res.font.roboto_bold, FontWeight.Bold),
)

/** Roboto Mono - logs, codes, anything that must align in columns. */
@Composable
internal fun robotoMonoFamily(): FontFamily = FontFamily(
    Font(Res.font.roboto_mono_regular, FontWeight.Normal),
)

/**
 * Builds the full [AppTypography] for one [FontSizeStep]. Sizes at MEDIUM
 * are calibrated to the app's PRE-EXISTING de-facto scale (body 13sp,
 * sections 14sp bold, titles 20sp bold...) so adopting the roles changed
 * nothing visually - the slider then scales the whole system.
 */
internal fun buildAppTypography(
    roboto: FontFamily,
    robotoMono: FontFamily,
    step: FontSizeStep,
): AppTypography {
    val f = step.factor
    fun style(
        size: Float,
        weight: FontWeight = FontWeight.Normal,
        family: FontFamily = roboto,
        lineHeight: Float = size * 1.35f,
        letterSpacing: Float = 0f,
    ) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = (size * f).sp,
        lineHeight = (lineHeight * f).sp,
        letterSpacing = letterSpacing.sp,
    )

    val material = Typography(
        displayLarge = style(57f),
        displayMedium = style(45f),
        displaySmall = style(36f),
        headlineLarge = style(32f),
        headlineMedium = style(28f),
        headlineSmall = style(24f),
        titleLarge = style(20f, FontWeight.Bold),
        titleMedium = style(16f, FontWeight.SemiBold, letterSpacing = 0.15f),
        titleSmall = style(14f, FontWeight.Bold, letterSpacing = 0.1f),
        bodyLarge = style(15f, letterSpacing = 0.25f),
        bodyMedium = style(13f, letterSpacing = 0.25f),
        bodySmall = style(12f, letterSpacing = 0.2f),
        labelLarge = style(13f, FontWeight.Medium, letterSpacing = 0.1f),
        labelMedium = style(12f, FontWeight.Medium, letterSpacing = 0.3f),
        labelSmall = style(11f, letterSpacing = 0.3f),
    )

    return AppTypography(
        material = material,
        screenTitle = style(20f, FontWeight.Bold),
        sectionTitle = style(14f, FontWeight.Bold, letterSpacing = 0.1f),
        statValue = style(16f, FontWeight.Bold),
        statLabel = style(11f, FontWeight.Medium, letterSpacing = 0.3f),
        logLine = style(11f, family = robotoMono, lineHeight = 16f),
        mono = style(14f, family = robotoMono),
    )
}
