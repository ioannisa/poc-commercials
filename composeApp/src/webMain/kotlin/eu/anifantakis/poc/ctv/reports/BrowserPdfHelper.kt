package eu.anifantakis.poc.ctv.reports

/**
 * Helper for handling PDF operations in the browser.
 * Uses JavaScript interop to trigger download/preview.
 */
expect object BrowserPdfHelper {
    /**
     * Trigger a PDF download in the browser
     * @param pdfBytes The PDF file content
     * @param fileName The suggested file name
     */
    fun downloadPdf(pdfBytes: ByteArray, fileName: String)

    /**
     * Open PDF in a new browser tab for preview
     * @param pdfBytes The PDF file content
     */
    fun previewPdf(pdfBytes: ByteArray)

    /**
     * Open PDF and trigger browser print dialog
     * @param pdfBytes The PDF file content
     */
    fun printPdf(pdfBytes: ByteArray)
}
