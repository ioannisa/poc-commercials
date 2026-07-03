package eu.anifantakis.ctv.reports

import eu.anifantakis.ctv.reports.models.ReportResult

/**
 * Generic report generation service - one API for every report; screens build
 * one or more [ReportPayload]s (see ProgramFlowReport.kt for the pattern) and
 * hand them in. Multiple payloads become ONE document in order - e.g. a whole
 * month as a batch of daily reports.
 *
 * Injected via Koin; the platform module binds the right implementation:
 * - JVM desktop: [DesktopReportService] - fills in-process via the shared engine
 * - Browsers (js/wasmJs): BrowserReportService - POSTs to the report server
 * - Android/iOS: [UnsupportedReportService]
 */
interface ReportService {
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
 * Bound on platforms that cannot generate reports (Android/iOS).
 */
class UnsupportedReportService : ReportService {

    override suspend fun exportToPdf(
        payloads: List<ReportPayload>,
        suggestedFileName: String
    ): ReportResult = unsupported()

    override suspend fun preview(payloads: List<ReportPayload>): ReportResult = unsupported()

    override suspend fun print(payloads: List<ReportPayload>): ReportResult = unsupported()

    override fun isReportGenerationAvailable(): Boolean = false

    private fun unsupported() = ReportResult.Error(
        "Report generation is not available on this platform. " +
            "Use the Desktop app or the web app instead."
    )
}
