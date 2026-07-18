package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.reports.models.ReportResult
import java.awt.Desktop
import java.io.File

/**
 * [PdfSink] for the DESKTOP: server-produced PDF bytes (the AI assistant's
 * out-of-band reports) land in a temp file and open in the system viewer.
 * Desktop's own reports keep the in-process Jasper path ([DesktopReportService]);
 * this sink exists for bytes that arrive ALREADY rendered.
 */
class DesktopPdfSink : PdfSink {

    override suspend fun save(bytes: ByteArray, fileName: String): ReportResult = open(bytes, fileName)

    override suspend fun preview(bytes: ByteArray, fileName: String): ReportResult = open(bytes, fileName)

    override suspend fun print(bytes: ByteArray, fileName: String): ReportResult = open(bytes, fileName)

    private fun open(bytes: ByteArray, fileName: String): ReportResult = try {
        val safe = fileName.substringBeforeLast('.').ifBlank { "report" }
        val file = File.createTempFile(safe, ".pdf").apply {
            writeBytes(bytes)
            deleteOnExit()
        }
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
        ReportResult.Success("Opened ${file.name}", file.absolutePath)
    } catch (e: Exception) {
        ReportResult.Error(e.message ?: "Failed to open PDF", e)
    }
}
