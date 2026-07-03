package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ReportResult

/**
 * Browser implementation of ReportService, shared by the js and wasmJs
 * targets. Reports are generated server-side via [ReportApiClient] (single
 * request or batch); the resulting PDF bytes are handed to the platform's
 * [BrowserPdfHelper].
 */
actual class ReportService actual constructor() {

    actual suspend fun exportToPdf(
        payloads: List<ReportPayload>,
        suggestedFileName: String
    ): ReportResult {
        return generate(payloads, suggestedFileName, "generate") { pdfBytes ->
            BrowserPdfHelper.downloadPdf(pdfBytes, suggestedFileName)
            ReportResult.Success("PDF downloaded: $suggestedFileName")
        }
    }

    actual suspend fun preview(payloads: List<ReportPayload>): ReportResult {
        return generate(payloads, fileName = null, verb = "preview") { pdfBytes ->
            BrowserPdfHelper.previewPdf(pdfBytes)
            ReportResult.Success("PDF opened in new tab")
        }
    }

    actual suspend fun print(payloads: List<ReportPayload>): ReportResult {
        return generate(payloads, fileName = null, verb = "print") { pdfBytes ->
            BrowserPdfHelper.printPdf(pdfBytes)
            ReportResult.Success("Print dialog opened")
        }
    }

    /**
     * Report generation runs on the server, reachable via the API. This is
     * optimistic (it does not probe the server - the API is sync); if the
     * server is down, the actual call reports the error. Use
     * ReportApiClient.checkServerStatus() for a real reachability check.
     */
    actual fun isReportGenerationAvailable(): Boolean = true

    private suspend fun generate(
        payloads: List<ReportPayload>,
        fileName: String?,
        verb: String,
        onPdf: (ByteArray) -> ReportResult
    ): ReportResult {
        if (payloads.isEmpty()) return ReportResult.Error("Nothing to $verb: the report is empty")
        return try {
            val result = if (payloads.size == 1) {
                ReportApiClient.generatePdf(payloads.single().toWire(fileName))
            } else {
                ReportApiClient.generatePdf(payloads.toWireBatch(fileName))
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

actual fun createReportService(): ReportService = ReportService()
