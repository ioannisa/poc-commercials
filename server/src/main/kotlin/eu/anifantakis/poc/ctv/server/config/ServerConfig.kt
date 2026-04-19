package eu.anifantakis.poc.ctv.server.config

import java.io.File
import java.util.Properties

data class ServerConfig(
    val mysqlJdbcUrl: String,
    val mysqlUsername: String,
    val mysqlPassword: String
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
            mysqlJdbcUrl = read(
                "mysql.jdbcUrl",
                "POC_DB_URL",
                "jdbc:mysql://localhost:3306/commercials?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8"
            ),
            mysqlUsername = read("mysql.username", "POC_DB_USER", "root"),
            mysqlPassword = read("mysql.password", "POC_DB_PASSWORD", "rootpass123")
        )
    }
}
