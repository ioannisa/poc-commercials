package eu.anifantakis.commercials.ui.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter

actual val nativeFilePickerAvailable: Boolean = true

/**
 * The real OS dialog: on macOS this is NSOpenPanel, on Windows the native
 * file chooser. Shown on the Swing/AWT event thread ([Dispatchers.Swing])
 * because `FileDialog` is modal - it runs its own event pump there until the
 * user picks a file or cancels, so the UI stays responsive.
 */
actual suspend fun pickFileNative(title: String, extension: String?): String? =
    withContext(Dispatchers.Swing) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        if (extension != null) {
            // macOS/Linux honour the filter callback; Windows honours the glob.
            dialog.filenameFilter = FilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
            dialog.file = "*.$extension"
        }
        dialog.isVisible = true
        val dir = dialog.directory
        val name = dialog.file
        if (dir != null && name != null) File(dir, name).absolutePath else null
    }
