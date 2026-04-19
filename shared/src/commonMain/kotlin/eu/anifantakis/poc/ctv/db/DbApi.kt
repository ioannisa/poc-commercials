package eu.anifantakis.poc.ctv.db

import eu.anifantakis.poc.ctv.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class DbUser(val username: String, val password: String)

@Serializable
private data class DemoUserDto(val username: String, val password: String)

private val httpClient by lazy {
    HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
}

/** Calls the app server's /api/demo/user. The server talks to MySQL; this code doesn't. */
suspend fun fetchDbUser(): DbUser {
    val baseUrl = AppConfig.require().serverBaseUrl
    val dto: DemoUserDto = httpClient.get("$baseUrl/api/demo/user").body()
    return DbUser(username = dto.username, password = dto.password)
}
