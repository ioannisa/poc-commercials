package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ReportConfig
import eu.anifantakis.poc.ctv.reports.models.ReportResult

/**
 * Service for generating reports.
 * Uses expect/actual pattern for platform-specific implementations.
 *
 * On JVM: Uses JasperReports for professional PDF generation
 * On other platforms: Falls back to HTML-based generation or shows unsupported message
 */
expect class ReportService() {
    /**
     * Generate and export a Program Flow report to PDF
     *
     * @param reportData The report data containing all items to display
     * @param config Report configuration (logo, paper size, etc.)
     * @param suggestedFileName The suggested file name for the PDF
     * @return ReportResult indicating success or failure
     */
    suspend fun exportProgramFlowToPdf(
        reportData: eu.anifantakis.poc.ctv.reports.models.ProgramFlowReportData,
        config: ReportConfig,
        suggestedFileName: String
    ): ReportResult

    /**
     * Preview a Program Flow report (opens in viewer)
     */
    suspend fun previewProgramFlow(
        reportData: eu.anifantakis.poc.ctv.reports.models.ProgramFlowReportData,
        config: ReportConfig
    ): ReportResult

    /**
     * Print a Program Flow report (opens print dialog)
     */
    suspend fun printProgramFlow(
        reportData: eu.anifantakis.poc.ctv.reports.models.ProgramFlowReportData,
        config: ReportConfig
    ): ReportResult

    /**
     * Check if JasperReports is available on this platform
     */
    fun isJasperReportsAvailable(): Boolean
}

/**
 * Factory function to create a ReportService instance
 */
expect fun createReportService(): ReportService
