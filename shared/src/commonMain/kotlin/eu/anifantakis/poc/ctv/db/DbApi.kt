package eu.anifantakis.poc.ctv.db

import eu.anifantakis.poc.ctv.auth.AuthSession
import eu.anifantakis.poc.ctv.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class DbUser(val username: String, val password: String)

@Serializable
private data class DemoUserDto(val username: String, val password: String)

/** Koin singleton - calls the app server's /api/demo/user. The server talks to MySQL; this code doesn't. */
class DbApi(private val session: AuthSession) {

    private val httpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            // Runs per request - picks up the current session token
            defaultRequest {
                session.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
        }
    }

    suspend fun fetchDbUser(): DbUser {
        val baseUrl = AppConfig.require().serverBaseUrl
        val dto: DemoUserDto = httpClient.get("$baseUrl/api/demo/user").body()
        return DbUser(username = dto.username, password = dto.password)
    }
}
