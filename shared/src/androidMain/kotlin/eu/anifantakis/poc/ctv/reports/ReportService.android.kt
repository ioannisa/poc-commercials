package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ReportResult

/**
 * Android implementation of ReportService.
 * Report generation is not available on Android; reports are generated on
 * Desktop, or in the browser via the report server.
 */
actual class ReportService actual constructor() {

    actual suspend fun exportToPdf(
        payloads: List<ReportPayload>,
        suggestedFileName: String
    ): ReportResult = unsupported()

    actual suspend fun preview(payloads: List<ReportPayload>): ReportResult = unsupported()

    actual suspend fun print(payloads: List<ReportPayload>): ReportResult = unsupported()

    actual fun isReportGenerationAvailable(): Boolean = false

    private fun unsupported() = ReportResult.Error(
        "Report generation is not available on Android. " +
            "Use the Desktop app or the web app instead."
    )
}

actual fun createReportService(): ReportService = ReportService()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
