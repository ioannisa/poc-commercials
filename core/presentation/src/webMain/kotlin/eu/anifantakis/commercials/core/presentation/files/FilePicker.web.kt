package eu.anifantakis.commercials.core.presentation.files

// Browsers cannot return an arbitrary server filesystem path - the migration
// screen falls back to its server-side file browser dialog here.
actual val nativeFilePickerAvailable: Boolean = false

actual suspend fun pickFileNative(title: String, extension: String?): String? = null

// Byte picking needs a DOM <input type=file> overlay on the canvas app - a
// v2 item; the upload buttons hide themselves meanwhile (desktop covers the
// admin upload flows).
actual val bytePickerAvailable: Boolean = false

actual suspend fun pickFileBytes(title: String, extension: String?): PickedFile? = null
