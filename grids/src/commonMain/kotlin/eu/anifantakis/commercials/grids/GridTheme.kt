package eu.anifantakis.commercials.grids

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Theme-aware grid colours. Grids draw a lot of chrome (headers, borders,
 * selection, the "modified" marker) that Material's colour roles don't
 * cover, so they get their own palette with an explicit light and dark
 * variant.
 *
 * Resolution is automatic: [gridPalette] picks the variant from the ACTIVE
 * MaterialTheme's background luminance, so whatever the app decides (locked
 * light, OS-following, a manual toggle) the grids follow with zero wiring.
 *
 * NOT in this palette: the scheduler's INTERIOR cells. Their colours are
 * DATA from the database - every programme has an operator-assigned colour
 * (legacy `programtypes.color`), so cells render the SAME in both themes,
 * always on a light "paper" surface; only frozen chrome (headers, totals)
 * follows the theme. See [SchedulerDataColors] and [contrastTextColor].
 */
@Immutable
data class GridPalette(
    val isDark: Boolean,
    // plain cells
    val cellBackground: Color,
    val cellText: Color,
    val cellBorder: Color,
    // chrome: headers, totals row, frozen edges
    val headerBackground: Color,
    val headerBorder: Color,
    val frozenRowHeader: Color,
    val gridBorderUnfocused: Color,
    // data-grid rows
    val rowAlternate: Color,
    val rowSelected: Color,
    val rowHovered: Color,
    val rowFocused: Color,
    val resizeHandle: Color,
    // value semantics
    val negativeValue: Color,
    val warningValue: Color,
    val positiveValue: Color,
    val mutedText: Color,
    // scheduler specifics
    val weekendColumn: Color,
    val selectedRowHeader: Color,
    val selectedColumnHeader: Color,
    val onSelectionHeader: Color,
    val selectionBorder: Color,
    val modifiedCellBackground: Color,
    val onModifiedCell: Color,
    // inline cell editor
    val editorBackground: Color,
)

/**
 * DATA colours - deliberately theme-INDEPENDENT. A programme's colour is its
 * identity (legacy `programtypes.color` was assigned per programme by the
 * operators), and the density heat-map follows the same rule: the same cell
 * shows the same colour whatever the theme. Text contrast is handled by
 * [contrastTextColor] instead.
 */
object SchedulerDataColors {
    val densityLow = Color(0xFF87CEEB)     // 1+ spots
    val densityMedium = Color(0xFF90EE90)  // 6+ spots
    val densityHigh = Color(0xFFFF69B4)    // 11+ spots
}

/**
 * Black or white - whichever contrasts with [background]. Programme colours
 * are operator-assigned in the legacy data and can be arbitrarily dark, so
 * text colour must follow the fill, not the theme.
 */
fun contrastTextColor(background: Color): Color =
    if (background.luminance() > 0.5f) Color.Black else Color.White

/** Exactly the colours the grids always had - the light look is unchanged. */
val LightGridPalette = GridPalette(
    isDark = false,
    cellBackground = Color.White,
    cellText = Color.Black,
    cellBorder = Color.LightGray,
    headerBackground = Color(0xFFE8E8E8),
    headerBorder = Color(0xFFBDBDBD),
    frozenRowHeader = Color(0xFFF5F5F5),
    gridBorderUnfocused = Color.Gray,
    rowAlternate = Color(0xFFF5F5F5),
    rowSelected = Color(0xFFBBDEFB),
    rowHovered = Color(0xFFE3F2FD),
    rowFocused = Color(0xFF90CAF9),
    resizeHandle = Color(0xFF9E9E9E),
    negativeValue = Color(0xFFD32F2F),
    warningValue = Color(0xFFFF9800),
    positiveValue = Color(0xFF388E3C),
    mutedText = Color.Gray,
    weekendColumn = Color(0xFFFFE0B2),
    selectedRowHeader = Color(0xFFE53935),
    selectedColumnHeader = Color(0xFFE53935),
    onSelectionHeader = Color.White,
    selectionBorder = Color(0xFFE53935),
    modifiedCellBackground = Color.Black,
    onModifiedCell = Color.White,
    editorBackground = Color.White,
)

/**
 * The dark counterpart: surfaces slightly above the Material dark background,
 * muted chrome, and the "modified" marker INVERTED (light chip on dark,
 * mirroring black-on-light) so it stays the loudest thing on the grid.
 */
val DarkGridPalette = GridPalette(
    isDark = true,
    cellBackground = Color(0xFF1E1F24),
    cellText = Color(0xFFE2E2E6),
    cellBorder = Color(0xFF3A3D45),
    headerBackground = Color(0xFF2A2C33),
    headerBorder = Color(0xFF4A4D55),
    frozenRowHeader = Color(0xFF26282E),
    gridBorderUnfocused = Color(0xFF5A5D65),
    rowAlternate = Color(0xFF23252B),
    rowSelected = Color(0xFF264A73),
    rowHovered = Color(0xFF2C3742),
    rowFocused = Color(0xFF2F5D8F),
    resizeHandle = Color(0xFF6A6D75),
    negativeValue = Color(0xFFEF9A9A),
    warningValue = Color(0xFFFFB74D),
    positiveValue = Color(0xFF81C784),
    mutedText = Color(0xFF9AA0A8),
    weekendColumn = Color(0xFF4A3A26),
    selectedRowHeader = Color(0xFFD94F4B),
    selectedColumnHeader = Color(0xFFD94F4B),
    onSelectionHeader = Color.White,
    selectionBorder = Color(0xFFEF5350),
    modifiedCellBackground = Color(0xFFE2E2E6),
    onModifiedCell = Color.Black,
    editorBackground = Color(0xFF2A2C33),
)

val LocalGridPalette = staticCompositionLocalOf { LightGridPalette }

/** The palette matching the ACTIVE MaterialTheme (dark surfaces -> dark grid). */
@Composable
fun gridPalette(): GridPalette =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DarkGridPalette else LightGridPalette

/** Entry-point wrapper: grid composables use this to expose the palette to their internals. */
@Composable
fun ProvideGridPalette(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGridPalette provides gridPalette(), content = content)
}
