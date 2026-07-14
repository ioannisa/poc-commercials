package eu.anifantakis.commercials.grids

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * The scheduler cell's number, MEASURED ONCE and drawn many times.
 *
 * A cell used to hold a real `Text` composable, and a month puts ~800 of them on
 * screen at once. Text is the most expensive thing Compose does - style resolution,
 * font matching, shaping, line breaking, and a layout node each - and it was being
 * paid per cell, every time the operator opened a month.
 *
 * ── Why a cache, when `drawText` already exists ──
 *
 * `drawText` comes in two forms: one takes a [TextMeasurer] and a string and MEASURES
 * AS IT DRAWS, the other takes a finished [TextLayoutResult] and only paints it. The
 * painting form needs someone to have measured already; this is that someone.
 *
 * The tempting explanation - "otherwise it would re-shape 800 paragraphs on every
 * frame" - is WRONG, and measuring says so: an idle frame allocates 1 KB with the
 * cache and 1 KB without it, because Compose does not re-run a draw lambda that
 * nothing invalidated; it replays the recorded picture.
 *
 * The real reason is what happens when the cells DO draw - opening a month, above
 * all. Without a cache each of the ~800 cells shapes its own paragraph; with one,
 * only the ~24 DISTINCT strings are shaped, because a cell's text is a spot count
 * ("7") or a duration ("05:42") and the same "7" measures identically everywhere.
 * Measured, on a real December: 9.8 MB and 21.7 ms to open a month without the
 * cache, 7.3 MB and ~19 ms with it.
 *
 * The COLOUR is deliberately not part of the key: `drawText` takes it at paint time,
 * so a cell whose programme colour forces white text shares its measurement with one
 * that draws black. Weight IS part of the key - bold is a different shape.
 *
 * Not thread-safe, and does not need to be: composition and the draw pass that
 * follows it both run on the UI thread.
 */
internal class CellTextCache(
    private val measurer: TextMeasurer,
    private val baseStyle: TextStyle,
    private val fontSize: TextUnit,
    private val cellWidthPx: Int,
) {
    private val regular = HashMap<String, TextLayoutResult>()
    private val bold = HashMap<String, TextLayoutResult>()

    /** Paragraphs actually shaped. Exposed so a benchmark can prove the cache works. */
    var measurements = 0
        private set

    fun layout(text: String, isBold: Boolean): TextLayoutResult {
        val cache = if (isBold) bold else regular
        return cache.getOrPut(text) { measure(text, isBold) }
    }

    /**
     * Measured EXACTLY the way the Text composable it replaces was: a paragraph as
     * WIDE AS THE CELL, centring the glyphs inside itself.
     *
     * Not pedantry. Measure it unconstrained instead and the paragraph comes out only
     * as wide as the digits; centring THAT box by hand lands the glyph run on a
     * different sub-pixel offset, Skia re-antialiases every stroke, and a pixel diff
     * against the old grid lights up all ~800 numbers - each a shade crisper, not one
     * of them the same.
     */
    private fun measure(text: String, isBold: Boolean): TextLayoutResult {
        measurements++
        return measurer.measure(
            text = text,
            style = baseStyle.copy(
                fontSize = fontSize,
                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            softWrap = false,
            constraints = Constraints.fixedWidth(cellWidthPx),
        )
    }
}

/**
 * One cache per (style, size, cell width). Rebuilt when the font-size preference
 * moves the grid's scale - which is exactly when every measurement in it goes stale.
 */
@Composable
internal fun rememberCellTextCache(
    baseStyle: TextStyle,
    fontSize: TextUnit,
    cellWidth: Dp,
): CellTextCache {
    val measurer = rememberTextMeasurer()
    val cellWidthPx = with(LocalDensity.current) { cellWidth.roundToPx() }
    return remember(measurer, baseStyle, fontSize, cellWidthPx) {
        CellTextCache(measurer, baseStyle, fontSize, cellWidthPx)
    }
}
