package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.reports.engine.ReportEngine
import eu.anifantakis.commercials.reports.models.ReportResult
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.dialogs.openFileWithDefaultApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import net.sf.jasperreports.engine.JasperPrint
import net.sf.jasperreports.engine.JasperPrintManager
import net.sf.jasperreports.engine.export.JRPrintServiceExporter
import net.sf.jasperreports.export.SimpleExporterInput
import net.sf.jasperreports.export.SimplePrintServiceExporterConfiguration
import java.io.File

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
            // Genuinely native save panel (NSSavePanel / IFileDialog / XDG
            // portal via FileKit) - the Swing JFileChooser is gone.
            val outputFile = showSaveDialog(suggestedFileName)
                ?: return ReportResult.Cancelled

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
            FileKit.openFileWithDefaultApplication(PlatformFile(tempFile))
            ReportResult.Success("Preview opened in default PDF viewer")
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

    private suspend fun showSaveDialog(suggestedFileName: String): File? {
        val picked = FileKit.openFileSaver(
            suggestedName = suggestedFileName.removeSuffix(".pdf"),
            defaultExtension = "pdf",
        ) ?: return null
        val file = picked.file
        // Ensure .pdf extension (some platforms let the user strip it)
        return if (file.name.lowercase().endsWith(".pdf")) file
        else File(file.absolutePath + ".pdf")
    }
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
