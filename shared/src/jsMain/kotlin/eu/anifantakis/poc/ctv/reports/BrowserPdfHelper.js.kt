package eu.anifantakis.poc.ctv.reports

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlinx.browser.document
import kotlinx.browser.window

/**
 * JS implementation of BrowserPdfHelper using browser APIs
 */
actual object BrowserPdfHelper {

    actual fun downloadPdf(pdfBytes: ByteArray, fileName: String) {
        val blob = createPdfBlob(pdfBytes)
        val url = URL.createObjectURL(blob)

        val link = document.createElement("a")
        link.asDynamic().href = url
        link.asDynamic().download = fileName
        link.asDynamic().click()

        // Clean up
        URL.revokeObjectURL(url)
    }

    actual fun previewPdf(pdfBytes: ByteArray) {
        val blob = createPdfBlob(pdfBytes)
        val url = URL.createObjectURL(blob)

        // Open in new tab
        window.open(url, "_blank")
    }

    actual fun printPdf(pdfBytes: ByteArray) {
        val blob = createPdfBlob(pdfBytes)
        val url = URL.createObjectURL(blob)

        // Create hidden iframe for printing
        val iframe = document.createElement("iframe")
        iframe.asDynamic().style.display = "none"
        iframe.asDynamic().src = url

        document.body?.appendChild(iframe)

        iframe.asDynamic().onload = {
            iframe.asDynamic().contentWindow.print()
            // Clean up after a delay to allow print dialog to open
            window.setTimeout({
                document.body?.removeChild(iframe)
                URL.revokeObjectURL(url)
            }, 1000)
        }
    }

    private fun createPdfBlob(pdfBytes: ByteArray): Blob {
        val uint8Array = Uint8Array(pdfBytes.size)
        for (i in pdfBytes.indices) {
            uint8Array[i] = pdfBytes[i]
        }
        return Blob(arrayOf(uint8Array), BlobPropertyBag(type = "application/pdf"))
    }
}
