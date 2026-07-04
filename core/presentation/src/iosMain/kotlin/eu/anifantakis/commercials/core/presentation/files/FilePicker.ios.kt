package eu.anifantakis.commercials.core.presentation.files

// No native filesystem-path picker on iOS (the migration screen is a
// desktop/web super-admin tool); fall back to the server-side file browser.
actual val nativeFilePickerAvailable: Boolean = false

actual suspend fun pickFileNative(title: String, extension: String?): String? = null
