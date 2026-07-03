package eu.anifantakis.commercials.auth

import eu.anifantakis.commercials.config.AppConfig
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
private data class StationAccessDto(
    val id: String,
    val name: String,
    val role: String,
    val clientCode: String? = null,
)

@Serializable
private data class LoginResponseDto(
    val token: String,
    val displayName: String,
    val isAdmin: Boolean = false,
    val stations: List<StationAccessDto> = emptyList(),
)

@Serializable
private data class ChangePasswordDto(val currentPassword: String, val newPassword: String)

@Serializable
private data class RecoverPasswordDto(val username: String, val recoveryCode: String, val newPassword: String)

@Serializable
private data class RecoveryCodesDto(val codes: List<String> = emptyList())

/**
 * Client for the server's /api/auth endpoints. On successful login the
 * session - including the user's per-station access list - is stored in the
 * injected [AuthSession] (encrypted, persistent).
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
                    // The super admin may log in with zero hosted stations
                    // (user management works regardless); everyone else needs
                    // at least one grant to have anything to see.
                    if (dto.stations.isEmpty() && !dto.isAdmin) {
                        Result.failure(Exception("No stations are assigned to this account"))
                    } else {
                        session.store(
                            token = dto.token,
                            displayName = dto.displayName,
                            isAdmin = dto.isAdmin,
                            stations = dto.stations.map {
                                StationAccess(it.id, it.name, it.role, it.clientCode)
                            }
                        )
                        Result.success(Unit)
                    }
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

    /**
     * Self-service password change. On success the server revokes ALL of the
     * user's sessions - the local session is cleared so the app returns to
     * the login screen.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit> {
        val token = session.token ?: return Result.failure(Exception("Not logged in"))
        return try {
            val response = httpClient.post("${AppConfig.require().serverBaseUrl}/api/auth/password") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(ChangePasswordDto(currentPassword, newPassword))
            }
            if (response.status.isSuccess()) {
                session.clear()
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.errorMessage()))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Could not reach the server: ${e.message}", e))
        }
    }

    /**
     * "Forgot password": consumes ONE recovery code to set a new password.
     * No session needed - this is how you get back in.
     */
    suspend fun recoverPassword(username: String, recoveryCode: String, newPassword: String): Result<Unit> {
        return try {
            val response = httpClient.post("${AppConfig.require().serverBaseUrl}/api/auth/recover") {
                contentType(ContentType.Application.Json)
                setBody(RecoverPasswordDto(username.trim(), recoveryCode.trim(), newPassword))
            }
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(Exception(response.errorMessage()))
        } catch (e: Exception) {
            Result.failure(Exception("Could not reach the server: ${e.message}", e))
        }
    }

    /**
     * Regenerates the user's one-time recovery codes. The returned raw codes
     * are shown ONCE - the server keeps only hashes, so save them now.
     */
    suspend fun regenerateRecoveryCodes(): Result<List<String>> {
        val token = session.token ?: return Result.failure(Exception("Not logged in"))
        return try {
            val response = httpClient.post("${AppConfig.require().serverBaseUrl}/api/auth/recovery-codes") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            if (response.status.isSuccess()) {
                val dto: RecoveryCodesDto = response.body()
                Result.success(dto.codes)
            } else {
                Result.failure(Exception(response.errorMessage()))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Could not reach the server: ${e.message}", e))
        }
    }
}

/** Best-effort extraction of the server's {"error": "..."} body. */
private suspend fun io.ktor.client.statement.HttpResponse.errorMessage(): String {
    val body = try { bodyAsText() } catch (_: Exception) { "" }
    val fromJson = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
    return fromJson ?: "Server error $status"
}
