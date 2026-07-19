package eu.anifantakis.commercials.server.config

import java.util.Properties

/**
 * The version this server BINARY was built as - baked into a generated
 * resource by server/build.gradle.kts from gradle.properties `serverVersion`.
 *
 * Distinct from the DESKTOP app's version advertisement (app_settings, served
 * by /version): this is a diagnostics stamp of the running process, that is
 * operational data about client releases.
 */
object ServerBuildInfo {
    val version: String by lazy {
        ServerBuildInfo::class.java.classLoader
            .getResourceAsStream("commercials-server-version.properties")
            ?.use { s -> Properties().apply { load(s) }.getProperty("version") }
            ?.takeIf { it.isNotBlank() }
            ?: "0.0.0" // resource missing = a build-script regression, not a runtime error
    }
}
