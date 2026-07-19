package eu.anifantakis.commercials.core.presentation.files

// No native filesystem-path picker on Android (the migration screen is a
// desktop/web super-admin tool); fall back to the server-side file browser.
actual val nativeFilePickerAvailable: Boolean = false

actual suspend fun pickFileNative(title: String, extension: String?): String? = null

// Admin upload flows are a desktop concern; no byte picker on Android.
actual val bytePickerAvailable: Boolean = false

actual suspend fun pickFileBytes(title: String, extension: String?): PickedFile? = null
