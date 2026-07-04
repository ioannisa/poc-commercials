package eu.anifantakis.commercials.db

import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.data.network.authenticatedJsonClient
import eu.anifantakis.commercials.core.data.config.AppConfig
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable

data class DbUser(val username: String, val password: String)

@Serializable
private data class DemoUserDto(val username: String, val password: String)

/** Koin singleton - calls the app server's /api/demo/user. The server talks to MySQL; this code doesn't. */
class DbApi(private val session: AuthSession) {

    // Shared client: bearer header per request + centralized 401 handling
    private val httpClient by lazy { authenticatedJsonClient(session) }

    suspend fun fetchDbUser(): DbUser {
        val baseUrl = AppConfig.require().serverBaseUrl
        val dto: DemoUserDto = httpClient.get("$baseUrl/api/demo/user").body()
        return DbUser(username = dto.username, password = dto.password)
    }
}
