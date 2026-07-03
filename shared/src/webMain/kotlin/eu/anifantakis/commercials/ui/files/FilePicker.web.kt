package eu.anifantakis.commercials.ui.files

// Browsers cannot return an arbitrary server filesystem path - the migration
// screen falls back to its server-side file browser dialog here.
actual val nativeFilePickerAvailable: Boolean = false

actual suspend fun pickFileNative(title: String, extension: String?): String? = null
