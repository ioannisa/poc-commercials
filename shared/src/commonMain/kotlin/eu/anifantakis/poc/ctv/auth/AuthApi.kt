package eu.anifantakis.poc.ctv.auth

import eu.anifantakis.poc.ctv.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class LoginRequestDto(val username: String, val password: String)

@Serializable
private data class LoginResponseDto(
    val token: String,
    val role: String,
    val displayName: String,
    val clientCode: String? = null,
)

/**
 * Client for the server's /api/auth endpoints. On successful login the
 * session is stored in the injected [AuthSession] (encrypted, persistent).
 */
class AuthApi(private val session: AuthSession) {

    private val httpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    suspend fun login(username: String, password: String): Result<Unit> {
        return try {
            val response = httpClient.post("${AppConfig.require().serverBaseUrl}/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequestDto(username, password))
            }
            when {
                response.status.isSuccess() -> {
                    val dto: LoginResponseDto = response.body()
                    session.store(dto.token, dto.role, dto.displayName, dto.clientCode)
                    Result.success(Unit)
                }
                response.status == HttpStatusCode.Unauthorized ->
                    Result.failure(Exception("Invalid username or password"))
                else ->
                    Result.failure(Exception("Server error ${response.status}: ${response.bodyAsText()}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Could not reach the server: ${e.message}", e))
        }
    }

    /**
     * Revokes the token server-side (best effort - tokens never expire, so
     * this is what actually kills them), then clears the local session.
     */
    suspend fun logout() {
        val token = session.token
        if (token != null) {
            try {
                httpClient.post("${AppConfig.require().serverBaseUrl}/api/auth/logout") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            } catch (_: Exception) {
                // Server unreachable - still log out locally
            }
        }
        session.clear()
    }
}
