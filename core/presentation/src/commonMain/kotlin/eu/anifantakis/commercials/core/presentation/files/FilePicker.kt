package eu.anifantakis.commercials.core.presentation.files

/**
 * A genuinely OS-native file-open dialog, where the platform can reach one.
 *
 * Only JVM desktop qualifies: `java.awt.FileDialog` shows the real macOS
 * (NSOpenPanel) / Windows dialog. Browsers, Android and iOS cannot hand back
 * an arbitrary filesystem path, so [nativeFilePickerAvailable] is false there
 * and callers fall back to the server-side file browser.
 *
 * Note: the returned path is a path on the CLIENT machine; the migration runs
 * server-side, so this is only correct when the desktop app talks to a
 * co-located (localhost) server. The server validates the path and reports a
 * clear error otherwise, so a wrong-machine pick fails safely.
 */
expect val nativeFilePickerAvailable: Boolean

/**
 * Opens the native open-file dialog and returns the chosen absolute path, or
 * null if the platform has no native picker or the user cancelled.
 * @param extension optional filter, without the dot (e.g. "sql").
 */
expect suspend fun pickFileNative(title: String, extension: String?): String?

/** A file picked for UPLOAD: its name and full content. */
data class PickedFile(val name: String, val bytes: ByteArray)

/**
 * Whether [pickFileBytes] can produce a file on this platform. Unlike
 * [pickFileNative] (which returns a CLIENT path, only useful against a
 * co-located server), a byte pick feeds an UPLOAD, so it works against a
 * remote server too. JVM desktop only for now; the admin upload flows hide
 * their buttons where this is false.
 */
expect val bytePickerAvailable: Boolean

/**
 * Opens the native open-file dialog and returns the chosen file's name and
 * CONTENT, or null if unavailable or cancelled.
 * @param extension optional filter, without the dot (e.g. "zip").
 */
expect suspend fun pickFileBytes(title: String, extension: String?): PickedFile?
