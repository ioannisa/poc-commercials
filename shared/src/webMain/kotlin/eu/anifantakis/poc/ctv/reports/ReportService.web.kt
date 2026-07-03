package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ReportResult

/**
 * Browser implementation of ReportService, shared by the js and wasmJs
 * targets. Reports are generated server-side via [ReportApiClient]; the
 * resulting PDF bytes are handed to the platform's [BrowserPdfHelper].
 */
actual class ReportService actual constructor() {

    actual suspend fun exportToPdf(
        payload: ReportPayload,
        suggestedFileName: String
    ): ReportResult {
        return generate(payload, suggestedFileName, "generate") { pdfBytes ->
            BrowserPdfHelper.downloadPdf(pdfBytes, suggestedFileName)
            ReportResult.Success("PDF downloaded: $suggestedFileName")
        }
    }

    actual suspend fun preview(payload: ReportPayload): ReportResult {
        return generate(payload, fileName = null, verb = "preview") { pdfBytes ->
            BrowserPdfHelper.previewPdf(pdfBytes)
            ReportResult.Success("PDF opened in new tab")
        }
    }

    actual suspend fun print(payload: ReportPayload): ReportResult {
        return generate(payload, fileName = null, verb = "print") { pdfBytes ->
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
        payload: ReportPayload,
        fileName: String?,
        verb: String,
        onPdf: (ByteArray) -> ReportResult
    ): ReportResult {
        return try {
            ReportApiClient.generatePdf(payload.toWire(fileName)).fold(
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
