package eu.anifantakis.commercials.update

import java.util.Properties

/**
 * The version this desktop build IS - baked into a generated resource by
 * desktopApp/build.gradle.kts from gradle.properties `appVersion` (the same
 * value that becomes the installer's packageVersion, so the running app and
 * its package can never disagree).
 *
 * This is one side of the auto-update comparison; the other side (the LATEST
 * published version) is runtime data the server serves on /version.
 */
object AppBuildInfo {
    val version: String by lazy {
        AppBuildInfo::class.java.classLoader
            .getResourceAsStream("commercials-app-version.properties")
            ?.use { s -> Properties().apply { load(s) }.getProperty("version") }
            ?.takeIf { it.isNotBlank() }
            ?: "0.0.0" // resource missing = a build-script regression, not a runtime error
    }
}
