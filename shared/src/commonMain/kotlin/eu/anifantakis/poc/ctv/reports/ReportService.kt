package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ReportResult

/**
 * Generic report generation service - one API for every report; screens build
 * one or more [ReportPayload]s (see ProgramFlowReport.kt for the pattern) and
 * hand them in. Multiple payloads become ONE document in order - e.g. a whole
 * month as a batch of daily reports.
 *
 * Platform actuals:
 * - JVM desktop: fills in-process via the shared JasperReports engine
 * - Browsers (js/wasmJs): POST the payload(s) to the report server
 * - Android/iOS: report generation is not available; every call returns an error
 */
expect class ReportService() {
    /**
     * Generate a PDF and save it (desktop: save dialog; browser: download).
     */
    suspend fun exportToPdf(payloads: List<ReportPayload>, suggestedFileName: String): ReportResult

    /**
     * Generate a PDF and open it for viewing.
     */
    suspend fun preview(payloads: List<ReportPayload>): ReportResult

    /**
     * Generate the report(s) and open the platform's print dialog.
     */
    suspend fun print(payloads: List<ReportPayload>): ReportResult

    /**
     * Whether this platform can generate reports at all. Optimistic on
     * browsers (it does not probe the report server).
     */
    fun isReportGenerationAvailable(): Boolean
}

/** Single-report conveniences. */
suspend fun ReportService.exportToPdf(payload: ReportPayload, suggestedFileName: String): ReportResult =
    exportToPdf(listOf(payload), suggestedFileName)

suspend fun ReportService.preview(payload: ReportPayload): ReportResult = preview(listOf(payload))

suspend fun ReportService.print(payload: ReportPayload): ReportResult = print(listOf(payload))

/**
 * Factory function to create a ReportService instance
 */
expect fun createReportService(): ReportService
