package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.reports.engine.ReportEngine
import eu.anifantakis.commercials.reports.models.ReportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import net.sf.jasperreports.engine.JasperPrint
import net.sf.jasperreports.engine.JasperPrintManager
import net.sf.jasperreports.engine.export.JRPrintServiceExporter
import net.sf.jasperreports.export.SimpleExporterInput
import net.sf.jasperreports.export.SimplePrintServiceExporterConfiguration
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * JVM/Desktop implementation of ReportService (Koin-bound on this platform).
 * Generates any report in-process via the shared generic [ReportEngine]
 * (same engine and templates the server uses). Multiple payloads are filled
 * separately and exported/printed as one document, in order.
 */
class DesktopReportService : ReportService {

    override suspend fun exportToPdf(
        payloads: List<ReportPayload>,
        suggestedFileName: String
    ): ReportResult {
        if (payloads.isEmpty()) return ReportResult.Error("Nothing to export: the report is empty")
        return try {
            // Swing components must be used on the EDT
            val outputFile = withContext(Dispatchers.Swing) {
                showSaveDialog(suggestedFileName)
            } ?: return ReportResult.Cancelled

            withContext(Dispatchers.IO) {
                ReportEngine.exportToPdfFile(fillAll(payloads), outputFile)
            }

            ReportResult.Success("PDF exported successfully", outputFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            ReportResult.Error("Failed to export PDF: ${e.message}", e)
        }
    }

    override suspend fun preview(payloads: List<ReportPayload>): ReportResult = withContext(Dispatchers.IO) {
        if (payloads.isEmpty()) return@withContext ReportResult.Error("Nothing to preview: the report is empty")
        try {
            // Create temp PDF file for preview
            val tempFile = File.createTempFile("report_preview_", ".pdf")
            tempFile.deleteOnExit()
            ReportEngine.exportToPdfFile(fillAll(payloads), tempFile)

            // Open in default PDF viewer
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(tempFile)
                ReportResult.Success("Preview opened in default PDF viewer")
            } else {
                ReportResult.Error("Desktop is not supported on this system")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ReportResult.Error("Failed to preview report: ${e.message}", e)
        }
    }

    override suspend fun print(payloads: List<ReportPayload>): ReportResult {
        if (payloads.isEmpty()) return ReportResult.Error("Nothing to print: the report is empty")
        return try {
            val jasperPrints = withContext(Dispatchers.IO) { fillAll(payloads) }

            // The print dialog is UI - show it from the EDT (the dialog is
            // modal, so blocking the EDT for its duration is expected)
            withContext(Dispatchers.Swing) {
                printWithDialog(jasperPrints)
            }

            ReportResult.Success("Print dialog opened")
        } catch (e: Exception) {
            e.printStackTrace()
            ReportResult.Error("Failed to print report: ${e.message}", e)
        }
    }

    override fun isReportGenerationAvailable(): Boolean = true

    private fun fillAll(payloads: List<ReportPayload>): List<JasperPrint> =
        payloads.map { ReportEngine.fill(it.toWire()) }

    private fun printWithDialog(jasperPrints: List<JasperPrint>) {
        if (jasperPrints.size == 1) {
            JasperPrintManager.printReport(jasperPrints.single(), true)
            return
        }
        // Batch print: one dialog, all documents in order
        val exporter = JRPrintServiceExporter()
        exporter.setExporterInput(SimpleExporterInput.getInstance(jasperPrints))
        exporter.setConfiguration(
            SimplePrintServiceExporterConfiguration().apply {
                isDisplayPrintDialog = true
                isDisplayPageDialog = false
            }
        )
        exporter.exportReport()
    }

    private fun showSaveDialog(suggestedFileName: String): File? {
        val fileChooser = JFileChooser()
        fileChooser.dialogTitle = "Save Report"
        fileChooser.selectedFile = File(suggestedFileName)
        fileChooser.fileFilter = FileNameExtensionFilter("PDF Files", "pdf")

        val result = fileChooser.showSaveDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            var file = fileChooser.selectedFile
            // Ensure .pdf extension
            if (!file.name.lowercase().endsWith(".pdf")) {
                file = File(file.absolutePath + ".pdf")
            }
            return file
        }
        return null
    }
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
