package eu.anifantakis.commercials.server.config

import java.io.File
import java.util.Properties

/**
 * Server process settings from config.properties. Database connections do
 * NOT live here - the central schema and the hosted stations are all defined
 * in stations.yaml (see stations/StationRegistry.kt).
 */
data class ServerConfig(
    val port: Int
)

object ServerConfigLoader {
    @Volatile private var cached: ServerConfig? = null

    fun get(): ServerConfig {
        cached?.let { return it }
        val loaded = load()
        cached = loaded
        return loaded
    }

    private fun load(): ServerConfig {
        val explicit = System.getProperty("poc.config") ?: System.getenv("POC_CONFIG")
        val file = if (explicit != null) File(explicit) else File("config.properties")

        val props = Properties()
        if (file.exists()) {
            file.inputStream().use { props.load(it) }
        }

        fun read(key: String, envKey: String, default: String): String =
            props.getProperty(key) ?: System.getenv(envKey) ?: default

        return ServerConfig(
            port = read("server.port", "POC_PORT", "8080").toIntOrNull() ?: 8080
        )
    }
}
