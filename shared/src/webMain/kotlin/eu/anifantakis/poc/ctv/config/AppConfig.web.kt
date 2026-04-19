package eu.anifantakis.poc.ctv.config

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

private val client by lazy { HttpClient() }

/** Fetches `/config.properties` over HTTP (served alongside the web page). */
internal actual suspend fun loadAppConfig(): AppConfig {
    val text = client.get("config.properties").bodyAsText()
    return parseProperties(text).toAppConfig()
}
