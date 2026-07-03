package eu.anifantakis.commercials.grids

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Theme-aware grid colours. Grids draw a lot of chrome (headers, borders,
 * zone tints, density heat-mapping, selection, the "modified" marker) that
 * Material's colour roles don't cover, so they get their own palette with an
 * explicit light and dark variant.
 *
 * Resolution is automatic: [gridPalette] picks the variant from the ACTIVE
 * MaterialTheme's background luminance, so whatever the app decides (locked
 * light, OS-following, a manual toggle) the grids follow with zero wiring.
 *
 * Server-sent zone colours: the API delivers zone tints as ARGB ints chosen
 * for the LIGHT theme (stable wire contract). [GridPalette.zoneTint] maps the
 * known palette to hand-picked dark counterparts and darkens anything unknown.
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
    val densityLow: Color,     // 1+ spots
    val densityMedium: Color,  // 6+ spots
    val densityHigh: Color,    // 11+ spots
    val zonePrime: Color,
    val zoneStandard: Color,
    val zoneSpecial: Color,
    val zoneDefault: Color,
    // inline cell editor
    val editorBackground: Color,
) {
    /**
     * Adapts a server-sent (light-theme) zone colour to this palette. Known
     * light values map to designed dark counterparts; unknown values are
     * darkened proportionally so custom zones stay readable.
     */
    fun zoneTint(raw: Color): Color {
        if (!isDark) return raw
        return darkZoneMap[raw] ?: lerp(raw, Color.Black, 0.62f)
    }

    private companion object {
        // light wire value -> dark rendering
        val darkZoneMap = mapOf(
            Color(0xFFFF69B4) to Color(0xFF8E3A63),  // prime pink
            Color(0xFF87CEEB) to Color(0xFF2E5F7A),  // standard sky blue
            Color(0xFF90EE90) to Color(0xFF2F6B3A),  // special green
            Color(0xFFFFE4B5) to Color(0xFF4A3A26),  // weekend moccasin (cells)
            Color(0xFFFFE0B2) to Color(0xFF4A3A26),  // weekend amber (headers)
            Color(0xFFFFFF99) to Color(0xFF6E6B2F),  // busy yellow
            Color(0xFFFFFFFF) to Color(0xFF1E1F24),  // "no zone" white -> cell bg
        )
    }
}

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
    densityLow = Color(0xFF87CEEB),
    densityMedium = Color(0xFF90EE90),
    densityHigh = Color(0xFFFF69B4),
    zonePrime = Color(0xFFFF69B4),
    zoneStandard = Color(0xFF87CEEB),
    zoneSpecial = Color(0xFF90EE90),
    zoneDefault = Color.White,
    editorBackground = Color.White,
)

/**
 * The dark counterpart: surfaces slightly above the Material dark background,
 * desaturated-but-recognisable zone hues, and the "modified" marker INVERTED
 * (light chip on dark, mirroring black-on-light) so it stays the loudest
 * thing on the grid.
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
    densityLow = Color(0xFF2E5F7A),
    densityMedium = Color(0xFF2F6B3A),
    densityHigh = Color(0xFF8E3A63),
    zonePrime = Color(0xFF8E3A63),
    zoneStandard = Color(0xFF2E5F7A),
    zoneSpecial = Color(0xFF2F6B3A),
    zoneDefault = Color(0xFF1E1F24),
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
