package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * THE GLYPH-FALLBACK CHAIN.
 *
 * Roboto ships 927 glyphs - Latin, Greek, Cyrillic - and nothing else. Ask it to
 * draw `עברית`, or one day Chinese, and it has nothing. Every text stack answers
 * that with FALLBACK: when the chosen face lacks a glyph, reach into another.
 *
 * ── Why this is done by hand ──
 *
 * Because Compose will not do it for us on the Skia targets, and the ways it
 * looks like it should are all dead ends. Each was tried, in a browser:
 *
 *  1. Add the Hebrew faces to [robotoFamily]. A FontFamily is a SELECTION list,
 *     not a fallback chain: Compose runs FontMatcher, picks ONE font per
 *     (weight, style), and hands Skia exactly one name -
 *     `FontLoadResult(typeface, listOf(font.cacheKey))`. Roboto wins every
 *     lookup; the Hebrew face is never even loaded. → tofu.
 *  2. `FontFamily.Resolver.preload()` the fallbacks. They load, they register -
 *     the browser fetches all three files, 200 OK - and Skia still never hears
 *     their names, because the alias list handed to the paragraph is still one
 *     long. → tofu.
 *  3. Wrap the resolver to merge the alias lists. `FontFamily.Resolver` is a
 *     `sealed interface`. → does not compile.
 *
 * Compose DOES pass a multi-name list, but only for the platform's own fonts.
 * That is why desktop, Android and iOS never showed the bug: they were quietly
 * falling through to a SYSTEM font. The browser has no system fonts, so it was
 * the only place honest enough to show us empty boxes.
 *
 * ── What this does instead ──
 *
 * Splits a string into runs by script and gives each run the family that can
 * actually draw it, through an [AnnotatedString]. Public API, every platform,
 * no internals. The B-side of that browser experiment already proved the second
 * half: name the family explicitly and Hebrew renders perfectly.
 *
 * ── Cost ──
 *
 * One scan per string, and it exits on the first character that needs nothing -
 * so the 99.9% of this app that is Greek and Latin pays a single loop over a
 * 40-character string and allocates nothing. Only text that actually contains a
 * foreign script builds spans.
 *
 * ── TO ADD A SCRIPT ──
 *
 * Drop the TTF in `composeResources/font` and add one [ScriptFont] to
 * [fallbackFontFamilies]. Nothing else changes - and the file is only downloaded
 * because it is listed, so a Chinese face costs Greek users nothing. That is the
 * whole reason for doing it this way rather than merging every script into one
 * enormous font.
 */
@Immutable
data class ScriptFont(
    /** Names the script, for diagnostics and tests. */
    val script: String,
    /** The codepoints this face is here to cover. Hebrew is `֐`..`׿`. */
    val ranges: List<CharRange>,
    val family: FontFamily,
) {
    // Indexed loop, not `ranges.any { }`: this runs once per CHARACTER of every
    // string the app draws, and a lambda + iterator per character is a real cost
    // at that rate. See the fast reject in GlyphFallback, which usually means we
    // never get here at all.
    fun covers(c: Char): Boolean {
        for (i in ranges.indices) if (c in ranges[i]) return true
        return false
    }
}

/**
 * Rewrites a string so every character is drawn by a face that has it.
 *
 * Almost always returns the text with no spans at all: this app is Greek and
 * Latin, and both live inside Roboto.
 */
@Immutable
class GlyphFallback(private val fonts: List<ScriptFont>) {

    // THE FAST REJECT, and the reason this is cheap enough to run on every cell
    // of a 1,500-cell grid.
    //
    // Everything Roboto covers - Latin, Greek (U+0370..), Cyrillic (U+0400..) -
    // sits BELOW the first fallback codepoint (Hebrew starts at U+0590), and CJK
    // and Arabic sit above the Latin block too. So one comparison per character
    // settles it, with no lambda, no iterator and no allocation. Only a character
    // inside the fallback window pays for the real lookup.
    private val lo: Char = fonts.minOfOrNull { f -> f.ranges.minOf { it.first } } ?: Char.MAX_VALUE
    private val hi: Char = fonts.maxOfOrNull { f -> f.ranges.maxOf { it.last } } ?: Char.MIN_VALUE

    private fun faceFor(c: Char): ScriptFont? {
        if (c < lo || c > hi) return null
        for (i in fonts.indices) if (fonts[i].covers(c)) return fonts[i]
        return null
    }

    /**
     * NULL means "this string needs nothing from me - draw it as it is".
     *
     * Null rather than an unstyled AnnotatedString, and that is the whole point:
     * Compose has a genuinely cheaper path for plain strings
     * (`TextStringSimpleNode`, no span handling) and a heavier one for annotated
     * ones. Wrapping every label in an AnnotatedString "just in case" would give
     * up that fast path for EVERY text in the app in order to serve the handful
     * that need a second font. So the 99.9% keeps its String, untouched.
     */
    fun annotateOrNull(text: String): AnnotatedString? {
        if (fonts.isEmpty() || text.isEmpty()) return null

        // Does anything in here even need another face? Usually not - and this
        // loop is where we find that out, in one pass, allocating nothing.
        var i = 0
        while (i < text.length && faceFor(text[i]) == null) i++
        if (i == text.length) return null

        return buildAnnotatedString {
            append(text)
            var start = 0
            var current: ScriptFont? = faceFor(text[0])
            for (j in 1..text.length) {
                val face = if (j < text.length) faceFor(text[j]) else null
                if (face === current) continue
                // The run [start, j) belongs to `current` - style it if it needs a face.
                current?.let { addStyle(SpanStyle(fontFamily = it.family), start, j) }
                start = j
                current = face
            }
        }
    }
}

/**
 * The active chain. Provided by `CommercialsTheme`; read by `AppText` and handed
 * to the leaf grid toolkits, which draw most of the app's text and cannot see
 * this module.
 *
 * Empty by default so a Compose preview - or a test that skips the theme - still
 * renders instead of throwing.
 */
val LocalGlyphFallback = staticCompositionLocalOf { GlyphFallback(emptyList()) }

/**
 * Draws [text] with the app's glyph fallback applied - and, when it needs none
 * (which is nearly always), through Compose's PLAIN-STRING fast path.
 *
 * That branch is the reason this exists instead of a one-liner: handing Compose
 * an AnnotatedString forces the heavier text node even for a label that is pure
 * Greek. Here the cost is one scan, and nothing else.
 */
@Composable
fun FallbackText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = LocalTextStyle.current,
) {
    val fallback = LocalGlyphFallback.current
    val annotated = remember(text, fallback) { fallback.annotateOrNull(text) }
    if (annotated == null) {
        Text(
            text = text, modifier = modifier, color = color, fontSize = fontSize,
            fontWeight = fontWeight, textAlign = textAlign, textDecoration = textDecoration,
            maxLines = maxLines, overflow = overflow, style = style,
        )
    } else {
        Text(
            text = annotated, modifier = modifier, color = color, fontSize = fontSize,
            fontWeight = fontWeight, textAlign = textAlign, textDecoration = textDecoration,
            maxLines = maxLines, overflow = overflow, style = style,
        )
    }
}

@Composable
internal fun rememberGlyphFallback(fonts: List<ScriptFont>): GlyphFallback =
    remember(fonts) { GlyphFallback(fonts) }
