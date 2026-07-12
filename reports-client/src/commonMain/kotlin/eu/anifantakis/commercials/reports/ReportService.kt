package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.reports.models.ReportResult

/**
 * Generic report generation service - one API for every report; screens build
 * one or more [ReportPayload]s (see ProgramFlowReport.kt for the pattern) and
 * hand them in. Multiple payloads become ONE document in order - e.g. a whole
 * month as a batch of daily reports.
 *
 * Injected via Koin; the platform module binds the right implementation:
 * - JVM desktop: [DesktopReportService] - fills in-process via the shared engine
 * - Everything else: [ServerReportService] - POSTs to the report server and
 *   hands the bytes to the platform's [PdfSink] (browser download / SAF /
 *   Files.app). Reports work on ALL FIVE platforms.
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
