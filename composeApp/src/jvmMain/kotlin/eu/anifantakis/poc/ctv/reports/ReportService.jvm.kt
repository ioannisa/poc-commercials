package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ProgramFlowReportData
import eu.anifantakis.poc.ctv.reports.models.ReportConfig
import eu.anifantakis.poc.ctv.reports.models.ReportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sf.jasperreports.engine.*
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource
import net.sf.jasperreports.pdf.JRPdfExporter
import net.sf.jasperreports.export.SimpleExporterInput
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput
import net.sf.jasperreports.pdf.SimplePdfExporterConfiguration
import java.awt.Desktop
import java.io.File
import java.io.InputStream
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

actual class ReportService actual constructor() {

    actual suspend fun exportProgramFlowToPdf(
        reportData: ProgramFlowReportData,
        config: ReportConfig,
        suggestedFileName: String
    ): ReportResult = withContext(Dispatchers.IO) {
        try {
            // Show file save dialog
            val outputFile = showSaveDialog(suggestedFileName) ?: return@withContext ReportResult.Cancelled

            // Generate the report
            val jasperPrint = generateJasperPrint(reportData, config)

            // Export to PDF
            exportToPdf(jasperPrint, outputFile)

            ReportResult.Success("PDF exported successfully", outputFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            ReportResult.Error("Failed to export PDF: ${e.message}", e)
        }
    }

    actual suspend fun previewProgramFlow(
        reportData: ProgramFlowReportData,
        config: ReportConfig
    ): ReportResult = withContext(Dispatchers.IO) {
        try {
            // Generate the report
            val jasperPrint = generateJasperPrint(reportData, config)

            // Create temp PDF file for preview
            val tempFile = File.createTempFile("report_preview_", ".pdf")
            tempFile.deleteOnExit()

            // Export to temp PDF
            exportToPdf(jasperPrint, tempFile)

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

    actual suspend fun printProgramFlow(
        reportData: ProgramFlowReportData,
        config: ReportConfig
    ): ReportResult = withContext(Dispatchers.IO) {
        try {
            // Generate the report
            val jasperPrint = generateJasperPrint(reportData, config)

            // Print using JasperReports print manager
            net.sf.jasperreports.engine.JasperPrintManager.printReport(jasperPrint, true)

            ReportResult.Success("Print dialog opened")
        } catch (e: Exception) {
            e.printStackTrace()
            ReportResult.Error("Failed to print report: ${e.message}", e)
        }
    }

    actual fun isJasperReportsAvailable(): Boolean = true

    private fun generateJasperPrint(
        reportData: ProgramFlowReportData,
        config: ReportConfig
    ): JasperPrint {
        // Load the JRXML template using context classloader
        val templateStream: InputStream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("reports/ProgramFlowReport.jrxml")
            ?: javaClass.getResourceAsStream("/reports/ProgramFlowReport.jrxml")
            ?: throw IllegalStateException("Report template not found: reports/ProgramFlowReport.jrxml")

        // JasperReports 7.x uses Jackson for XML parsing - load and compile
        val content = templateStream.bufferedReader().use { it.readText() }
        val jasperContext = net.sf.jasperreports.engine.DefaultJasperReportsContext.getInstance()
        val contentStream = java.io.ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
        val jasperDesign = net.sf.jasperreports.engine.xml.JRXmlLoader.load(jasperContext, contentStream)
        val jasperReport = JasperCompileManager.getInstance(jasperContext).compile(jasperDesign)

        // Prepare parameters
        val parameters = mutableMapOf<String, Any?>()
        parameters["REPORT_TITLE"] = reportData.title
        parameters["REPORT_DATE"] = reportData.dateFormatted
        parameters["EMPTY_TIME"] = reportData.emptyTimeFormatted
        parameters["LOGO_PATH"] = config.logoPath

        // Create data source from items
        val dataSource = JRBeanCollectionDataSource(reportData.items)

        // Fill the report
        return JasperFillManager.fillReport(jasperReport, parameters, dataSource)
    }

    private fun exportToPdf(jasperPrint: JasperPrint, outputFile: File) {
        val exporter = JRPdfExporter()
        exporter.setExporterInput(SimpleExporterInput(jasperPrint))
        exporter.setExporterOutput(SimpleOutputStreamExporterOutput(outputFile))

        val configuration = SimplePdfExporterConfiguration()
        configuration.isCreatingBatchModeBookmarks = true
        exporter.setConfiguration(configuration)

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

actual fun createReportService(): ReportService = ReportService()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
