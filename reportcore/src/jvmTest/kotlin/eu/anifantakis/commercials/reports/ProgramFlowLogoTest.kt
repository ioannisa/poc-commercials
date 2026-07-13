package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.reports.dto.ReportRequest
import eu.anifantakis.commercials.reports.engine.ReportEngine
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.sf.jasperreports.engine.JRPrintImage
import net.sf.jasperreports.engine.JRPrintText
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The station logo (`server.yaml` -> `stations[].logo` -> `LOGO_PATH`).
 *
 * Two rules, and the second is the one that matters operationally: a logo that
 * cannot be loaded must cost you THE LOGO, never the report. The template says
 * so (`onErrorType="Blank"` on the image, plus a placeholder that prints only
 * when no path is set) - this proves it, because a station whose logo file has
 * been moved or renamed is a Tuesday, not an incident.
 */
class ProgramFlowLogoTest {

    private fun request(logoPath: Any?) = ReportRequest(
        reportId = "ProgramFlowReport",
        parameters = JsonObject(
            mapOf(
                "REPORT_TITLE" to JsonPrimitive("ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ"),
                "REPORT_DATE" to JsonPrimitive("Δευτέρα - 13/07/2026"),
                "EMPTY_TIME" to JsonPrimitive("Κενός Χρόνος: 00:00"),
                "LOGO_PATH" to (logoPath?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
            )
        ),
        rows = listOf(
            JsonObject(
                mapOf(
                    "message" to JsonPrimitive("AKEK  06/2026 πριν δελτίο"),
                    "timeSlot" to JsonPrimitive("20:30"),
                    "duration" to JsonPrimitive("00:33"),
                    "program" to JsonPrimitive("ΕΙΔΗΣΕΙΣ"),
                    "notes" to JsonPrimitive(""),
                    "groupTotalDuration" to JsonPrimitive("00:33"),
                    "groupSpotCount" to JsonPrimitive(1),
                )
            )
        ),
    )

    private fun texts(request: ReportRequest) = ReportEngine.fill(request)
        .pages.flatMap { it.elements }

    @Test
    fun `a configured logo prints, and replaces the placeholder`() {
        val png = File.createTempFile("station-logo", ".png").apply {
            deleteOnExit()
            ImageIO.write(
                BufferedImage(120, 48, BufferedImage.TYPE_INT_RGB).apply {
                    createGraphics().run { paint = Color.ORANGE; fillRect(0, 0, 120, 48); dispose() }
                },
                "png",
                this,
            )
        }

        val elements = texts(request(png.absolutePath))

        assertTrue(
            elements.any { it is JRPrintImage },
            "the station's logo must be printed when server.yaml configures one",
        )
        assertFalse(
            elements.filterIsInstance<JRPrintText>().any { it.fullText == "LOGO" },
            "the 'LOGO' placeholder must give way to the real logo",
        )
    }

    @Test
    fun `no logo prints the placeholder, not an error`() {
        val elements = texts(request(null))

        assertTrue(
            elements.filterIsInstance<JRPrintText>().any { it.fullText == "LOGO" },
            "a station with no logo gets the placeholder",
        )
    }

    @Test
    fun `a broken logo path still produces the report`() {
        // The file is gone / renamed / on a drive that did not mount. The report
        // is the deliverable; the logo is decoration. It must still print.
        val elements = texts(request("/definitely/not/a/file/station.png"))

        assertTrue(
            elements.filterIsInstance<JRPrintText>().any { it.fullText == "ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ" },
            "an unreadable logo must not take the report down with it",
        )
    }
}
