package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.reports.dto.ReportRequest
import eu.anifantakis.commercials.reports.engine.ReportEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import net.sf.jasperreports.engine.JRPrintText
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the Program Flow report with the WORST-CASE message (the longest one
 * observed in production data) and writes it to /tmp for inspection.
 *
 * The bug this guards: the template used `textAdjust="ScaleFont"`, so a message
 * too long for its column had its FONT SHRUNK - rows ended up at different
 * sizes, which is exactly what the legacy report never did.
 */
class ProgramFlowLayoutTest {

    /**
     * The LONGEST programme name in the production data - 62 characters. 370 of
     * the 513 names need more than one line in a narrow gutter, which is why the
     * programme now gets a full-width line of its own.
     */
    private val LONG_PROGRAM = "ΒΗΜΑΤΑ ΣΤΟ ΝΟΤΟ Ε4  Πεζοπορική διαδρομή  Τρυπιδάκης Κατσιμπρής"

    /** One break = ONE programme; the notes column stays blank for handwriting. */
    private fun row(
        message: String,
        program: String,
        timeSlot: String = "20:30",
        notes: String = "",
    ) = JsonObject(
        mapOf(
            "message" to JsonPrimitive(message),
            "timeSlot" to JsonPrimitive(timeSlot),
            "duration" to JsonPrimitive("00:33"),
            "program" to JsonPrimitive(program),
            "notes" to JsonPrimitive(notes),
            "groupTotalDuration" to JsonPrimitive("10:00"),
            "groupSpotCount" to JsonPrimitive(20),
        )
    )

    @Test
    fun `renders long messages without shrinking the font`() {
        val request = ReportRequest(
            reportId = "ProgramFlowReport",
            parameters = JsonObject(
                mapOf(
                    "REPORT_TITLE" to JsonPrimitive("ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ"),
                    "REPORT_DATE" to JsonPrimitive("Δευτέρα - 13/07/2026"),
                    "EMPTY_TIME" to JsonPrimitive("Κενός Χρόνος: 00:00"),
                )
            ),
            rows = listOf(
                // break 1
                row("AKEK  06/2026 πριν δελτίο", "ΕΙΔΗΣΕΙΣ"),
                // the offender: 64 chars - needed 282pt in a 200pt column
                row("DALKIN ΞΕΝΙΚΑΚΗΣ \"έχεις επιλογή έχεις θέρμανση \" 12/2024 TB ΣΠΟΤ", "ΕΙΔΗΣΕΙΣ"),
                row("ΑΘΗΝΑΪΚΗ ΕΠΙΠΛΟΓΡΑΜΜΗ  Ν.1  ΤΡΑΠΕΖΑΡΙΑ  30/12/2024 TB ΣΠΟΤ", "ΕΙΔΗΣΕΙΣ"),
                row("MBS College  28/05/2026 TB ΣΠΟΤ", "ΕΙΔΗΣΕΙΣ"),
                // break 2 - a SECOND group, so the grouping itself is under test
                row("LOREAL  Paris 2o  Excellence  TB ΣΠΟΤ", "ΜΑΓΚΑΖΙΝΟ", timeSlot = "21:00"),
                row("VELVITA TB ΣΠΟΤ", "ΜΑΓΚΑΖΙΝΟ", timeSlot = "21:00"),
                // break 3 - the LONGEST real programme name. It wraps to several
                // lines in the left gutter, which is what used to push a strip of
                // dead white space into the message column (assertion 5).
                row("ΚΑΦΑΝΤΑΡΗΣ CARPARTS ( X )  TB ΣΠΟΤ", LONG_PROGRAM, timeSlot = "22:00"),
                row("ΖΙΝΩΝΑΣ ΚΟΥΦΩΜΑΤΑ  Ν.2 cut  NEW  ( X )  09/2023 TB ΣΠΟΤ", LONG_PROGRAM, timeSlot = "22:00"),
            ),
        )
        val print = ReportEngine.fill(request)

        val texts = print.pages.flatMap { it.elements }.filterIsInstance<JRPrintText>()

        // 1. The whole report is Roboto - the app's own UI typeface, registered
        //    as a Jasper font extension (fonts/roboto). A missing extension does
        //    not fall back silently; it throws JRFontNotFoundException.
        val families = texts.map { it.fontName }.toSet()
        assertEquals(setOf("Roboto"), families, "every element must render in Roboto")

        // 2. THE REGRESSION THIS FILE EXISTS FOR: the template used
        //    textAdjust="ScaleFont", so a message too long for its column had its
        //    FONT SHRUNK - rows printed at different sizes. Every DETAIL row must
        //    now share one size, including the 64-char worst case.
        // Select the message cells by their VALUE (element coordinates shift with
        // the band layout; the text does not).
        val messages = request.rows.map { row -> row["message"]!!.jsonPrimitive.content }
        val messageSizes = texts
            .filter { element -> element.fullText in messages }
            .map { element -> element.fontSize }
            .toSet()
        assertEquals(
            1, messageSizes.size,
            "message column must use ONE font size, found $messageSizes (ScaleFont is back?)",
        )

        // 3. Nothing is silently cut: the worst-case message survives whole.
        assertTrue(
            texts.any { element -> element.fullText?.endsWith("12/2024 TB ΣΠΟΤ") == true },
            "the long message must print in full",
        )
        // Jasper sets textTruncateIndex when it had to CUT a value to fit.
        val truncated = texts.filter { element -> element.textTruncateIndex != null }
            .mapNotNull { element -> element.fullText }
        assertTrue(truncated.isEmpty(), "nothing may be cut off; truncated: $truncated")

        // 4. ONE BREAK = ONE PROGRAMME. The time and the programme are a group
        //    HEADING, so each must print once per break - not once per spot.
        //    The old layout (ProgramFlowReportOld.jrxml) repeated both on every
        //    row, which is the thing the owner called out: a break has exactly
        //    one programme, so stating it 19 times is noise, and it cost the
        //    message column the width it needed.
        //    Break 1 = 20:30 / ΕΙΔΗΣΕΙΣ over 4 spots, break 2 = 21:00 / ΜΑΓΚΑΖΙΝΟ
        //    over 2. If either ever prints 4 or 2 times, the fields have leaked
        //    back into the detail band.
        fun timesPrinted(value: String) = texts.count { it.fullText == value }
        assertEquals(1, timesPrinted("20:30"), "the break time belongs to the heading, not the rows")
        assertEquals(1, timesPrinted("21:00"), "the break time belongs to the heading, not the rows")
        assertEquals(1, timesPrinted("ΕΙΔΗΣΕΙΣ"), "one break = one programme; print it once")
        assertEquals(1, timesPrinted("ΜΑΓΚΑΖΙΝΟ"), "one break = one programme; print it once")
        assertEquals(1, timesPrinted(LONG_PROGRAM), "one break = one programme; print it once")

        // 5. NO DEAD SPACE UNDER THE HEADING RULE. The programme sits in the left
        //    gutter and wraps - four lines for LONG_PROGRAM. The rule is anchored
        //    to the BOTTOM of the group header (positionType), so the first spot
        //    starts right beneath it however tall the gutter grows. Anchored to
        //    the top instead, that growth opened a white strip across the message
        //    column, which is the defect this guards.
        //    Measured on the WORST case: break 3, whose programme is 4 lines.
        fun yOfTop(value: String) = texts.first { it.fullText == value }.y
        val firstSpotTop = texts.first { it.fullText == "ΚΑΦΑΝΤΑΡΗΣ CARPARTS ( X )  TB ΣΠΟΤ" }.y
        // the heading rule = the LAST line above that spot (not the previous
        // break's footer separator, which also sits above it)
        val ruleBottom = print.pages.flatMap { it.elements }
            .filterIsInstance<net.sf.jasperreports.engine.JRPrintLine>()
            .filter { it.y < firstSpotTop }
            .maxOf { it.y + it.height }
        val gap = firstSpotTop - ruleBottom
        assertTrue(
            gap in 0..12,
            "the first spot must start right under the heading rule; gap was ${gap}pt " +
                "(the rule has drifted up - is positionType=FixRelativeToBottom still on it?)",
        )

        // 6. The column headers sit directly on their rule - they are not dragged
        //    apart by anything above them.
        val heading = texts.filter { it.fullText == "Μήνυμα" }.first { it.y > yOfTop("22:00") }
        val headerToRule = ruleBottom - (heading.y + heading.height)
        assertTrue(
            headerToRule in 0..10,
            "the headers must sit directly on the rule; gap was ${headerToRule}pt " +
                "(did they lose positionType=FixRelativeToBottom?)",
        )

        // 7. THE PROGRAMME GETS ITS OWN FULL-WIDTH LINE, BESIDE THE TIME.
        //    It used to be stacked UNDER the time in a 95pt gutter - but 370 of
        //    the 513 real programme names do not fit a column that narrow (the
        //    longest is 62 chars), so they wrapped, grew the heading band, and
        //    prised the column headers away from their own rule. Widening or
        //    re-anchoring could not fix it: the programme and the headers were
        //    competing for the same vertical space.
        //    So: it must sit to the RIGHT of the time (not below it), and on ONE
        //    line. LONG_PROGRAM is the worst case in the data.
        val time22 = texts.first { it.fullText == "22:00" }
        val programText = texts.first { it.fullText == LONG_PROGRAM }
        assertTrue(
            programText.x > time22.x + time22.width,
            "the programme belongs BESIDE the time, not stacked under it in a gutter",
        )
        assertTrue(
            programText.height <= 26,
            "the longest programme name must fit ONE line; it rendered ${programText.height}pt tall " +
                "(its column has been narrowed - the gutter problem is back)",
        )

        // 8. Everything in the right-hand block shares ONE left edge: the
        //    programme, the Μήνυμα header, and the spot rows beneath it. A stray
        //    few points of indent on any of them reads as a misalignment.
        val leftEdges = setOf(
            programText.x,
            heading.x,
            texts.first { it.fullText == "ΚΑΦΑΝΤΑΡΗΣ CARPARTS ( X )  TB ΣΠΟΤ" }.x,
        )
        assertEquals(
            1, leftEdges.size,
            "programme, Μήνυμα header and the spot rows must share one left edge; got $leftEdges",
        )

        val out = File("/tmp/program-flow-layout.pdf")
        ReportEngine.exportToPdfFile(print, out)
        println("PDF -> ${out.absolutePath} (${out.length()} bytes), fonts=$families, messageSizes=$messageSizes")
    }
}
