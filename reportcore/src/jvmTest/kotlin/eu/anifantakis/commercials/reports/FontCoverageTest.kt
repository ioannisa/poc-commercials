package eu.anifantakis.commercials.reports

import java.awt.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE BUNDLED FACES MUST COVER EVERY SCRIPT THE APP SHIPS A LANGUAGE FOR.
 *
 * Stock Roboto has 927 glyphs - Latin, Greek, Cyrillic, and no Hebrew. For a
 * long time nobody noticed, because desktop, Android and iOS quietly borrowed a
 * Hebrew face from the operating system. The BROWSER has no system fonts to
 * borrow, so Hebrew came out as tofu boxes there and only there.
 *
 * Listing a second font in the FontFamily does NOT fix it - Compose picks one
 * font per (weight, style); a family is a selection list, not a fallback chain -
 * and JasperReports does no glyph fallback either. So the coverage has to live
 * INSIDE the file, and `roboto_hebrew_*.ttf` is Roboto with Noto Sans Hebrew
 * merged in (see core/presentation/licenses/NOTICE-fonts.md).
 *
 * This test guards that: drop stock Roboto back in and it fails, naming the
 * script that just went missing - instead of a user finding it in a browser.
 * These are the same files the Compose UI loads from composeResources.
 */
class FontCoverageTest {

    private val faces = listOf(
        "roboto_hebrew_regular",
        "roboto_hebrew_medium",
        "roboto_hebrew_bold",
        "roboto_mono_hebrew_regular",
    )

    /** One representative letter per script the app has a language for. */
    private val scripts = mapOf(
        "Latin (English/Deutsch/Italiano/Français)" to 'A',
        "Greek (Ελληνικά - the primary language)" to 'Α',
        "Cyrillic (Русский)" to 'А',
        "HEBREW (עברית - the one Roboto lacks)" to 'א',
    )

    @Test
    fun `every bundled face covers every script the app ships`() {
        for (face in faces) {
            val stream = javaClass.classLoader.getResourceAsStream("fonts/roboto/$face.ttf")
                ?: error("font resource missing: fonts/roboto/$face.ttf")
            val font = stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }

            val missing = scripts.filterValues { !font.canDisplay(it) }.keys
            assertTrue(
                missing.isEmpty(),
                "$face cannot render: ${missing.joinToString()} - has stock Roboto " +
                    "(no Hebrew) been dropped back in? See NOTICE-fonts.md.",
            )
        }
    }

    @Test
    fun `the merged faces keep Roboto's own metrics`() {
        // The Hebrew was scaled to Roboto's 2048 em and Roboto's vertical metrics
        // were copied back, so merging reflowed nothing. If a future merge skips
        // that, every screen in the app shifts.
        val heights = faces.map { face ->
            javaClass.classLoader.getResourceAsStream("fonts/roboto/$face.ttf")!!
                .use { Font.createFont(Font.TRUETYPE_FONT, it) }
                .let { font ->
                    java.awt.Canvas().getFontMetrics(font.deriveFont(100f)).height
                }
        }
        assertEquals(
            1, heights.take(3).toSet().size,
            "the three proportional weights must share one line height; got $heights",
        )
    }
}
