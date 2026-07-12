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

    /** One break = ONE programme; the notes column stays blank for handwriting. */
    private fun row(message: String, program: String, notes: String = "") = JsonObject(
        mapOf(
            "message" to JsonPrimitive(message),
            "timeSlot" to JsonPrimitive("20:30"),
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
                row("AKEK  06/2026 πριν δελτίο", "ΕΙΔΗΣΕΙΣ"),
                // the offender: 64 chars - needed 282pt in a 200pt column
                row("DALKIN ΞΕΝΙΚΑΚΗΣ \"έχεις επιλογή έχεις θέρμανση \" 12/2024 TB ΣΠΟΤ", "ΕΙΔΗΣΕΙΣ"),
                row("ΑΘΗΝΑΪΚΗ ΕΠΙΠΛΟΓΡΑΜΜΗ  Ν.1  ΤΡΑΠΕΖΑΡΙΑ  30/12/2024 TB ΣΠΟΤ", "ΕΙΔΗΣΕΙΣ"),
                row("MBS College  28/05/2026 TB ΣΠΟΤ", "ΕΙΔΗΣΕΙΣ"),
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

        val out = File("/tmp/program-flow-layout.pdf")
        ReportEngine.exportToPdfFile(print, out)
        println("PDF -> ${out.absolutePath} (${out.length()} bytes), fonts=$families, messageSizes=$messageSizes")
    }
}
