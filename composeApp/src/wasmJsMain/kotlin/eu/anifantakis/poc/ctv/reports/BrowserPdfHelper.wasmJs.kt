package eu.anifantakis.poc.ctv.reports

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set

/**
 * WASM implementation of BrowserPdfHelper using external JS functions
 */
actual object BrowserPdfHelper {

    actual fun downloadPdf(pdfBytes: ByteArray, fileName: String) {
        val uint8Array = byteArrayToUint8Array(pdfBytes)
        downloadPdfJs(uint8Array, fileName)
    }

    actual fun previewPdf(pdfBytes: ByteArray) {
        val uint8Array = byteArrayToUint8Array(pdfBytes)
        previewPdfJs(uint8Array)
    }

    actual fun printPdf(pdfBytes: ByteArray) {
        val uint8Array = byteArrayToUint8Array(pdfBytes)
        printPdfJs(uint8Array)
    }

    private fun byteArrayToUint8Array(bytes: ByteArray): Uint8Array {
        val uint8Array = Uint8Array(bytes.size)
        for (i in bytes.indices) {
            uint8Array[i] = bytes[i]
        }
        return uint8Array
    }
}

// External JS functions for PDF operations
@JsFun("""
(uint8Array, fileName) => {
    const blob = new Blob([uint8Array], { type: 'application/pdf' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.click();
    URL.revokeObjectURL(url);
}
""")
private external fun downloadPdfJs(uint8Array: Uint8Array, fileName: String)

@JsFun("""
(uint8Array) => {
    const blob = new Blob([uint8Array], { type: 'application/pdf' });
    const url = URL.createObjectURL(blob);
    window.open(url, '_blank');
}
""")
private external fun previewPdfJs(uint8Array: Uint8Array)

@JsFun("""
(uint8Array) => {
    const blob = new Blob([uint8Array], { type: 'application/pdf' });
    const url = URL.createObjectURL(blob);
    const iframe = document.createElement('iframe');
    iframe.style.display = 'none';
    iframe.src = url;
    document.body.appendChild(iframe);
    iframe.onload = () => {
        iframe.contentWindow.print();
        setTimeout(() => {
            document.body.removeChild(iframe);
            URL.revokeObjectURL(url);
        }, 1000);
    };
}
""")
private external fun printPdfJs(uint8Array: Uint8Array)
