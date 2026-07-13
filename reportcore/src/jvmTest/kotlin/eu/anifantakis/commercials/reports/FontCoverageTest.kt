package eu.anifantakis.commercials.reports

import java.awt.Font
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WHAT A REPORT CAN PRINT, AND WHY IT IS NOT THE SAME AS WHAT A SCREEN CAN.
 *
 * The UI has a glyph-fallback chain: a character Roboto cannot draw is handed to
 * a face that can (core/presentation, FontFallback.kt), so the app renders Hebrew
 * today and Chinese the day someone adds the file.
 *
 * JASPERREPORTS HAS NO SUCH THING. It picks the family named in the template and
 * draws with it, full stop - a character the face lacks comes out as an empty box
 * IN THE PDF, where nobody notices until a customer opens it. So a report can
 * print exactly what its own face covers, and that is the contract this test
 * pins down.
 *
 * Roboto covers Latin, Greek and Cyrillic - which is the whole of a report's
 * content: station data, message text, programme names, customer names. Fine.
 * But if a report ever has to carry Hebrew, Arabic or Chinese, adding the font to
 * the UI's chain WILL NOT be enough - this file has to change too, and the second
 * test below is here to make that impossible to forget.
 */
class FontCoverageTest {

    private val faces = listOf(
        "roboto_regular",
        "roboto_medium",
        "roboto_bold",
        "roboto_mono_regular",
    )

    private fun face(name: String): Font =
        javaClass.classLoader.getResourceAsStream("fonts/roboto/$name.ttf")
            ?.use { Font.createFont(Font.TRUETYPE_FONT, it) }
            ?: error("font resource missing: fonts/roboto/$name.ttf")

    @Test
    fun `every report face covers every script a report actually contains`() {
        val required = mapOf(
            "Latin" to 'A',
            "Greek (the reports are Greek)" to 'Α',
            "Cyrillic" to 'А',
        )
        for (name in faces) {
            val font = face(name)
            val missing = required.filterValues { !font.canDisplay(it) }.keys
            assertTrue(
                missing.isEmpty(),
                "$name cannot render: ${missing.joinToString()} - reports in that script " +
                    "would print empty boxes, and Jasper does no fallback to save them.",
            )
        }
    }

    @Test
    fun `the reports honestly do NOT cover Hebrew - and this test is the reminder`() {
        // Not a defect: stock Roboto simply has no Hebrew, and no report needs it.
        // This assertion exists so that the day one does, the build says so out
        // loud instead of shipping a PDF full of boxes. When that day comes:
        // register a Hebrew-capable face in jasperreports-fonts.xml and give the
        // template a family that has it - the UI's fallback chain cannot help here.
        assertFalse(
            face("roboto_regular").canDisplay('א'),
            "roboto_regular now HAS Hebrew - has the report font been changed? If a " +
                "report must print Hebrew, Jasper needs its own face for it (it does " +
                "no glyph fallback); update jasperreports-fonts.xml and this test.",
        )
    }
}
