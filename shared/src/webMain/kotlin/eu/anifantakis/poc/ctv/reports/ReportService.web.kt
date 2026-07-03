package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ReportResult

/**
 * Browser implementation of ReportService (Koin-bound on js and wasmJs).
 * Reports are generated server-side via the injected [ReportApiClient]
 * (single request or batch); the resulting PDF bytes are handed to the
 * platform's [BrowserPdfHelper].
 */
class BrowserReportService(private val api: ReportApiClient) : ReportService {

    override suspend fun exportToPdf(
        payloads: List<ReportPayload>,
        suggestedFileName: String
    ): ReportResult {
        return generate(payloads, suggestedFileName, "generate") { pdfBytes ->
            BrowserPdfHelper.downloadPdf(pdfBytes, suggestedFileName)
            ReportResult.Success("PDF downloaded: $suggestedFileName")
        }
    }

    override suspend fun preview(payloads: List<ReportPayload>): ReportResult {
        return generate(payloads, fileName = null, verb = "preview") { pdfBytes ->
            BrowserPdfHelper.previewPdf(pdfBytes)
            ReportResult.Success("PDF opened in new tab")
        }
    }

    override suspend fun print(payloads: List<ReportPayload>): ReportResult {
        return generate(payloads, fileName = null, verb = "print") { pdfBytes ->
            BrowserPdfHelper.printPdf(pdfBytes)
            ReportResult.Success("Print dialog opened")
        }
    }

    /**
     * Report generation runs on the server, reachable via the API. This is
     * optimistic (it does not probe the server - the API is sync); if the
     * server is down, the actual call reports the error.
     */
    override fun isReportGenerationAvailable(): Boolean = true

    private suspend fun generate(
        payloads: List<ReportPayload>,
        fileName: String?,
        verb: String,
        onPdf: (ByteArray) -> ReportResult
    ): ReportResult {
        if (payloads.isEmpty()) return ReportResult.Error("Nothing to $verb: the report is empty")
        return try {
            val result = if (payloads.size == 1) {
                api.generatePdf(payloads.single().toWire(fileName))
            } else {
                api.generatePdf(payloads.toWireBatch(fileName))
            }
            result.fold(
                onSuccess = onPdf,
                onFailure = { error ->
                    ReportResult.Error("Failed to $verb PDF: ${error.message}", error)
                }
            )
        } catch (e: Exception) {
            ReportResult.Error("Failed to $verb PDF: ${e.message}", e)
        }
    }
}
