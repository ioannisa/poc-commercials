package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.reports.models.ReportResult
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.dialogs.openFileWithDefaultApplication
import io.github.vinceglb.filekit.dialogs.shareFile
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.write

/**
 * iOS destination for server-rendered PDF bytes, all genuinely native OS
 * objects: UIDocumentPicker "Save to Files" export, QuickLook/system viewer
 * to open, the share sheet (which carries Print/AirPrint) for printing.
 *
 * (Near-twin of the Android sink on purpose - see its note.)
 */
class FileKitPdfSink : PdfSink {

    override suspend fun save(bytes: ByteArray, fileName: String): ReportResult {
        val target = FileKit.openFileSaver(
            suggestedName = fileName.removeSuffix(".pdf"),
            defaultExtension = "pdf",
        ) ?: return ReportResult.Cancelled
        target.write(bytes)
        return ReportResult.Success("PDF saved", target.path)
    }

    override suspend fun preview(bytes: ByteArray, fileName: String): ReportResult {
        val file = FileKit.cacheDir / fileName
        file.write(bytes)
        FileKit.openFileWithDefaultApplication(file)
        return ReportResult.Success("PDF opened")
    }

    override suspend fun print(bytes: ByteArray, fileName: String): ReportResult {
        val file = FileKit.cacheDir / fileName
        file.write(bytes)
        // The share sheet carries Print on both mobile OSes - one line, real
        // OS chrome. A dedicated PrintManager adapter can come later.
        FileKit.shareFile(file)
        return ReportResult.Success("Share sheet opened")
    }
}
