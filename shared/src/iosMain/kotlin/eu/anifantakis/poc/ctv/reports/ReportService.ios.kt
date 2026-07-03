package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ReportResult
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS implementation of ReportService.
 * Report generation is not available on iOS; reports are generated on
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
        "Report generation is not available on iOS. " +
            "Use the Desktop app or the web app instead."
    )
}

actual fun createReportService(): ReportService = ReportService()

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
