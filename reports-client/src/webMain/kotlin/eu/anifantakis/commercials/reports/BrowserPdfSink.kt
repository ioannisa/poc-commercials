package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.reports.models.ReportResult

/**
 * Browser destination for server-rendered PDF bytes: download, new tab,
 * hidden-iframe window.print - the pre-existing BrowserPdfHelper, adapted
 * onto the PdfSink seam ServerReportService consumes.
 */
class BrowserPdfSink : PdfSink {

    override suspend fun save(bytes: ByteArray, fileName: String): ReportResult {
        BrowserPdfHelper.downloadPdf(bytes, fileName)
        return ReportResult.Success("PDF downloaded: $fileName")
    }

    override suspend fun preview(bytes: ByteArray, fileName: String): ReportResult {
        BrowserPdfHelper.previewPdf(bytes)
        return ReportResult.Success("PDF opened in new tab")
    }

    override suspend fun print(bytes: ByteArray, fileName: String): ReportResult {
        BrowserPdfHelper.printPdf(bytes)
        return ReportResult.Success("Print dialog opened")
    }
}
