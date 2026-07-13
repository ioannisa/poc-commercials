package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.ui.text.font.FontFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.unit.isUnspecified
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The glyph-fallback chain: the app's answer to Roboto having no Hebrew (and no
 * Chinese, and no Arabic), and to Compose not falling back for bundled fonts on
 * the Skia targets - see [GlyphFallback] for what was tried first.
 *
 * The two properties that matter, and both bite if broken:
 *
 *  - it MUST fire, or foreign scripts render as empty boxes in the browser;
 *  - it must NOT fire on ordinary text, or every Greek label in a 1,500-cell
 *    grid allocates spans it does not need.
 */
class GlyphFallbackTest {

    private val hebrew = FontFamily.Cursive     // a stand-in; identity is what we assert
    private val chinese = FontFamily.Monospace

    private val chain = GlyphFallback(
        listOf(
            ScriptFont("Hebrew", listOf('֐'..'׿'), hebrew),
            ScriptFont("Chinese", listOf('一'..'鿿'), chinese),
        )
    )

    @Test
    fun `text Roboto already covers is left completely alone`() {
        // The 99.9% case. No spans - not even empty ones - so the grids pay a
        // single scan and allocate nothing.
        for (text in listOf(
            "ΛΕΝΑΚΑΚΗΣ ΧΡΩΜΑΤΑ ΣΙΔΗΡΙΚΑ",   // Greek
            "LOREAL Elvive Glycolic Gloss",  // Latin
            "Русский",                       // Cyrillic
            "00:33",
            "",
        )) {
            assertNull(
                chain.annotateOrNull(text),
                "'$text' needs no fallback - it MUST come back null, so Compose keeps " +
                    "its cheaper plain-string text node instead of the annotated one.",
            )
        }
    }

    @Test
    fun `a Hebrew word is handed to the Hebrew face`() {
        val out = chain.annotateOrNull("שלום")!!

        assertEquals("שלום", out.text)
        assertEquals(1, out.spanStyles.size)
        val span = out.spanStyles.single()
        assertEquals(hebrew, span.item.fontFamily)
        assertEquals(0, span.start)
        assertEquals(4, span.end)
    }

    @Test
    fun `MIXED text switches face per run, and only for the runs that need it`() {
        // The real shape of this app's data: a Greek/Latin sentence with one
        // foreign word in it. Only that word may be re-faced - re-facing the
        // whole string would drag Greek into a font that has no Greek.
        val out = chain.annotateOrNull("Γλώσσα: עברית (Hebrew)")!!

        assertEquals("Γλώσσα: עברית (Hebrew)", out.text)
        assertEquals(1, out.spanStyles.size)

        val span = out.spanStyles.single()
        assertEquals(hebrew, span.item.fontFamily)
        assertEquals(
            "עברית",
            out.text.substring(span.start, span.end),
            "the span must cover exactly the Hebrew run, nothing around it",
        )
    }

    @Test
    fun `each script goes to its OWN face - adding one does not disturb the others`() {
        val out = chain.annotateOrNull("א 中 b")!!

        assertEquals(2, out.spanStyles.size)
        val families = out.spanStyles.associate { out.text.substring(it.start, it.end) to it.item.fontFamily }
        assertEquals(hebrew, families["א"])
        assertEquals(chinese, families["中"])
    }

    @Test
    fun `the span changes the FACE and nothing else - so bold Hebrew stays bold`() {
        // The span must name a family and stop there. Give it a weight, a size or
        // a colour and it would flatten the very thing it was called in to help:
        // a bold Hebrew word inside a bold sentence would come back regular, and a
        // red error message would go black at the first Hebrew character.
        //
        // Weight, size and colour therefore come from the enclosing TextStyle, and
        // reach the Hebrew face because the fallback family declares the SAME three
        // weights as Roboto (see fallbackFontFamilies).
        val span = chain.annotateOrNull("Σφάλμα: עברית")!!.spanStyles.single().item

        assertEquals(hebrew, span.fontFamily)
        assertNull(span.fontWeight, "the run's weight must pass through, not be overwritten")
        assertNull(span.fontStyle, "the run's italic/upright must pass through")
        assertTrue(span.color.isUnspecified, "the run's colour must pass through - error text stays red")
        assertTrue(span.fontSize.isUnspecified, "the run's size must pass through")
    }

    @Test
    fun `an empty chain is a no-op, so previews and tests still render`() {
        assertNull(GlyphFallback(emptyList()).annotateOrNull("שלום"))
    }
}
