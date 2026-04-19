package eu.anifantakis.poc.ctv.config

data class AppConfig(
    val serverBaseUrl: String
) {
    companion object {
        private var cached: AppConfig? = null

        suspend fun load(): AppConfig {
            cached?.let { return it }
            val loaded = loadAppConfig()
            cached = loaded
            return loaded
        }

        fun require(): AppConfig =
            cached ?: error("AppConfig not loaded yet; call AppConfig.load() before first use")
    }
}

/** Platform-specific read of `config.properties`. */
internal expect suspend fun loadAppConfig(): AppConfig

/**
 * Minimal .properties parser: `key=value` per line, `#` or `!` start comments,
 * blank lines ignored. Good enough for the POC's config file.
 */
internal fun parseProperties(text: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq).trim()
        val value = line.substring(eq + 1).trim()
        out[key] = value
    }
    return out
}

internal fun Map<String, String>.toAppConfig(): AppConfig = AppConfig(
    serverBaseUrl = this["server.baseUrl"]
        ?: error("Missing required key 'server.baseUrl' in config.properties")
)
