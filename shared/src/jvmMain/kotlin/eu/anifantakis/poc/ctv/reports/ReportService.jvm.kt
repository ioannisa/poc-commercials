package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.engine.ReportEngine
import eu.anifantakis.poc.ctv.reports.models.ReportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import net.sf.jasperreports.engine.JasperPrintManager
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * JVM/Desktop implementation of ReportService.
 * Generates any report in-process via the shared generic [ReportEngine]
 * (same engine and templates the server uses).
 */
actual class ReportService actual constructor() {

    actual suspend fun exportToPdf(
        payload: ReportPayload,
        suggestedFileName: String
    ): ReportResult {
        return try {
            // Swing components must be used on the EDT
            val outputFile = withContext(Dispatchers.Swing) {
                showSaveDialog(suggestedFileName)
            } ?: return ReportResult.Cancelled

            withContext(Dispatchers.IO) {
                val jasperPrint = ReportEngine.fill(payload.toWire(suggestedFileName))
                ReportEngine.exportToPdfFile(jasperPrint, outputFile)
            }

            ReportResult.Success("PDF exported successfully", outputFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            ReportResult.Error("Failed to export PDF: ${e.message}", e)
        }
    }

    actual suspend fun preview(payload: ReportPayload): ReportResult = withContext(Dispatchers.IO) {
        try {
            val jasperPrint = ReportEngine.fill(payload.toWire())

            // Create temp PDF file for preview
            val tempFile = File.createTempFile("report_preview_", ".pdf")
            tempFile.deleteOnExit()
            ReportEngine.exportToPdfFile(jasperPrint, tempFile)

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

    actual suspend fun print(payload: ReportPayload): ReportResult {
        return try {
            val jasperPrint = withContext(Dispatchers.IO) {
                ReportEngine.fill(payload.toWire())
            }

            // The print dialog is UI - show it from the EDT (the dialog is
            // modal, so blocking the EDT for its duration is expected)
            withContext(Dispatchers.Swing) {
                JasperPrintManager.printReport(jasperPrint, true)
            }

            ReportResult.Success("Print dialog opened")
        } catch (e: Exception) {
            e.printStackTrace()
            ReportResult.Error("Failed to print report: ${e.message}", e)
        }
    }

    actual fun isReportGenerationAvailable(): Boolean = true

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

actual fun createReportService(): ReportService = ReportService()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
