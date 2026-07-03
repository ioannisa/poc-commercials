package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ReportResult

/**
 * Generic report generation service - one API for every report; screens build
 * a [ReportPayload] (see ProgramFlowReport.kt for the pattern) and hand it in.
 *
 * Platform actuals:
 * - JVM desktop: fills in-process via the shared JasperReports engine
 * - Browsers (js/wasmJs): POST the payload to the report server
 * - Android/iOS: report generation is not available; every call returns an error
 */
expect class ReportService() {
    /**
     * Generate a PDF and save it (desktop: save dialog; browser: download).
     */
    suspend fun exportToPdf(payload: ReportPayload, suggestedFileName: String): ReportResult

    /**
     * Generate a PDF and open it for viewing.
     */
    suspend fun preview(payload: ReportPayload): ReportResult

    /**
     * Generate the report and open the platform's print dialog.
     */
    suspend fun print(payload: ReportPayload): ReportResult

    /**
     * Whether this platform can generate reports at all. Optimistic on
     * browsers (it does not probe the report server).
     */
    fun isReportGenerationAvailable(): Boolean
}

/**
 * Factory function to create a ReportService instance
 */
expect fun createReportService(): ReportService
