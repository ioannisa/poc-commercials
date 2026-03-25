package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ProgramFlowReportData
import eu.anifantakis.poc.ctv.reports.models.ReportConfig
import eu.anifantakis.poc.ctv.reports.models.ReportResult
import kotlin.js.Date

/**
 * JavaScript implementation of ReportService.
 * Uses server-side report generation via the Ktor backend API.
 */
actual class ReportService actual constructor() {

    actual suspend fun exportProgramFlowToPdf(
        reportData: ProgramFlowReportData,
        config: ReportConfig,
        suggestedFileName: String
    ): ReportResult {
        return try {
            val result = ReportApiClient.generateProgramFlowPdf(
                reportData = reportData,
                config = config,
                fileName = suggestedFileName
            )

            result.fold(
                onSuccess = { pdfBytes ->
                    BrowserPdfHelper.downloadPdf(pdfBytes, suggestedFileName)
                    ReportResult.Success("PDF downloaded: $suggestedFileName")
                },
                onFailure = { error ->
                    ReportResult.Error(
                        "Failed to generate PDF: ${error.message}",
                        error
                    )
                }
            )
        } catch (e: Exception) {
            ReportResult.Error("Failed to generate PDF: ${e.message}", e)
        }
    }

    actual suspend fun previewProgramFlow(
        reportData: ProgramFlowReportData,
        config: ReportConfig
    ): ReportResult {
        return try {
            val result = ReportApiClient.generateProgramFlowPdf(
                reportData = reportData,
                config = config,
                fileName = "preview.pdf"
            )

            result.fold(
                onSuccess = { pdfBytes ->
                    BrowserPdfHelper.previewPdf(pdfBytes)
                    ReportResult.Success("PDF opened in new tab")
                },
                onFailure = { error ->
                    ReportResult.Error(
                        "Failed to preview PDF: ${error.message}",
                        error
                    )
                }
            )
        } catch (e: Exception) {
            ReportResult.Error("Failed to preview PDF: ${e.message}", e)
        }
    }

    actual suspend fun printProgramFlow(
        reportData: ProgramFlowReportData,
        config: ReportConfig
    ): ReportResult {
        return try {
            val result = ReportApiClient.generateProgramFlowPdf(
                reportData = reportData,
                config = config,
                fileName = "print.pdf"
            )

            result.fold(
                onSuccess = { pdfBytes ->
                    BrowserPdfHelper.printPdf(pdfBytes)
                    ReportResult.Success("Print dialog opened")
                },
                onFailure = { error ->
                    ReportResult.Error(
                        "Failed to print PDF: ${error.message}",
                        error
                    )
                }
            )
        } catch (e: Exception) {
            ReportResult.Error("Failed to print PDF: ${e.message}", e)
        }
    }

    /**
     * JasperReports runs on the server, but reports are available via API
     */
    actual fun isJasperReportsAvailable(): Boolean = true
}

actual fun createReportService(): ReportService = ReportService()

actual fun currentTimeMillis(): Long = Date.now().toLong()
