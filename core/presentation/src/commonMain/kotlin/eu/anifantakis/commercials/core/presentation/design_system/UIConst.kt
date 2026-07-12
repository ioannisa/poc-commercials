package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale (kmp-developer skill convention): no magic dp values in
 * screens — gutters, insets and gaps name a rung of this ladder.
 *
 * DELIBERATELY IDENTICAL ON EVERY PLATFORM. Spacing expresses visual
 * GROUPING, which is platform-neutral; what differs per OS is CONTROL
 * geometry, and that lives in [PlatformVisualTokens], not here. Keeping the
 * ladder invariant makes the literal migration provably pixel-neutral:
 * `8.dp -> UIConst.paddingSmall` cannot change a single frame on any target.
 *
 * The two extra rungs below the skill's default ladder (hairline 2, compact
 * 12) exist because this app's screens already use those values heavily
 * (2.dp x17, 12.dp x18 at migration time).
 *
 * NOT spacing (and therefore NOT in here): control heights, corner radii,
 * icon sizes -> [PlatformVisualTokens]; domain/data geometry (a timetable
 * column width, a programme cell height) -> named local constants at the
 * owning site.
 */
object UIConst {
    val paddingHairline = 2.dp
    val paddingExtraSmall = 4.dp
    val paddingSmall = 8.dp
    val paddingCompact = 12.dp
    val paddingRegular = 16.dp
    val paddingAverage = 24.dp
    val paddingDouble = 32.dp
    val paddingTriple = 48.dp
    val paddingQuadruple = 64.dp

    fun grayOutColor(color: Color, blendFactor: Float = 0.5f) = lerp(color, Color.Gray, blendFactor)
    fun dimOutColor(color: Color, blendFactor: Float = 0.4f) = lerp(color, Color.Black, blendFactor)
}
