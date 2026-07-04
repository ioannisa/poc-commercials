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
