package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.reports.models.ReportResult

/**
 * What a platform DOES with server-rendered PDF bytes - the only thing that
 * actually differs between browsers and mobile. Bound per platform in Koin:
 * web = BrowserPdfSink (download / new tab / window.print), android + iOS =
 * FileKitPdfSink (SAF / Files.app save, system viewer, share sheet).
 */
interface PdfSink {
    suspend fun save(bytes: ByteArray, fileName: String): ReportResult
    suspend fun preview(bytes: ByteArray, fileName: String): ReportResult
    suspend fun print(bytes: ByteArray, fileName: String): ReportResult
}

/**
 * ReportService for every platform that renders server-side (browsers AND
 * mobile - the successor of the web-only BrowserReportService, and the end
 * of UnsupportedReportService: reports now work on all five platforms).
 * Desktop keeps its in-process Jasper engine (offline, no round trip).
 */
class ServerReportService(
    private val api: ReportApiClient,
    private val sink: PdfSink,
) : ReportService {

    override suspend fun exportToPdf(
        payloads: List<ReportPayload>,
        suggestedFileName: String
    ): ReportResult = generate(payloads, suggestedFileName, "generate") { bytes ->
        sink.save(bytes, suggestedFileName)
    }

    override suspend fun preview(payloads: List<ReportPayload>): ReportResult =
        generate(payloads, fileName = null, verb = "preview") { bytes ->
            sink.preview(bytes, "report_preview.pdf")
        }

    override suspend fun print(payloads: List<ReportPayload>): ReportResult =
        generate(payloads, fileName = null, verb = "print") { bytes ->
            sink.print(bytes, "report_print.pdf")
        }

    /** Optimistic (no server probe); a down server reports on the actual call. */
    override fun isReportGenerationAvailable(): Boolean = true

    private suspend fun generate(
        payloads: List<ReportPayload>,
        fileName: String?,
        verb: String,
        onPdf: suspend (ByteArray) -> ReportResult
    ): ReportResult {
        if (payloads.isEmpty()) return ReportResult.Error("Nothing to $verb: the report is empty")
        return try {
            val result = if (payloads.size == 1) {
                api.generatePdf(payloads.single().toWire(fileName))
            } else {
                api.generatePdf(payloads.toWireBatch(fileName))
            }
            result.fold(
                onSuccess = { onPdf(it) },
                onFailure = { error ->
                    ReportResult.Error("Failed to $verb PDF: ${error.message}", error)
                }
            )
        } catch (e: Exception) {
            ReportResult.Error("Failed to $verb PDF: ${e.message}", e)
        }
    }
}
