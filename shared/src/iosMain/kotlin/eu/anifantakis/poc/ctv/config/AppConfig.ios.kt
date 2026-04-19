package eu.anifantakis.poc.ctv.config

import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Runtime config path (outside the app bundle, editable post-install):
 *   <App container>/Documents/config.properties
 *
 * On first launch — or any launch where that file doesn't exist — we copy
 * the bundled `config.properties` from the app bundle as a starting template.
 * The app always reads the external file, so user edits persist across app
 * updates. Expose the file to the user with UIFileSharingEnabled (iTunes/
 * Finder file sharing) or LSSupportsOpeningDocumentsInPlace (Files app) in
 * Info.plist.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual suspend fun loadAppConfig(): AppConfig {
    val docsDir = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, true
    ).firstOrNull() as? String
        ?: error("Documents directory unavailable")

    val externalPath = "$docsDir/config.properties"
    val fm = NSFileManager.defaultManager

    if (!fm.fileExistsAtPath(externalPath)) {
        val bundled = NSBundle.mainBundle.pathForResource("config", "properties")
            ?: error("Bundled config.properties not found (add it to the Xcode target's Copy Bundle Resources)")
        val defaults = NSString.stringWithContentsOfFile(bundled, NSUTF8StringEncoding, null)
            ?: error("Failed to read bundled config.properties")
        @Suppress("CAST_NEVER_SUCCEEDS")
        (defaults as NSString).writeToFile(externalPath, true, NSUTF8StringEncoding, null)
    }

    val text = NSString.stringWithContentsOfFile(externalPath, NSUTF8StringEncoding, null)
        ?: error("Failed to read $externalPath")
    return parseProperties(text).toAppConfig()
}
