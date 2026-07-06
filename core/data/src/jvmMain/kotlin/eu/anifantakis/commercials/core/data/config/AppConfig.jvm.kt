package eu.anifantakis.commercials.core.data.config

import java.io.File

/**
 * Read `config.properties` from the working directory, or from the path
 * given by the `-Dcommercials.config=...` JVM property, or the
 * `COMMERCIALS_CONFIG` env var.
 */
internal actual suspend fun loadAppConfig(): AppConfig {
    val explicit = System.getProperty("commercials.config") ?: System.getenv("COMMERCIALS_CONFIG")
    val file = if (explicit != null) File(explicit) else File("config.properties")
    require(file.exists()) { "config.properties not found at ${file.absolutePath}" }
    return parseProperties(file.readText()).toAppConfig()
}
