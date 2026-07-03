package eu.anifantakis.commercials.auth

import eu.anifantakis.commercials.config.AppConfig
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

/** One user's grant on one station, as managed by the super admin. */
@Serializable
data class AdminGrant(val stationId: String, val role: String, val clientCode: String? = null)

/** A managed user as listed by the admin API. */
@Serializable
data class AdminUser(
    val id: Long,
    val username: String,
    val displayName: String,
    val isAdmin: Boolean = false,
    val grants: List<AdminGrant> = emptyList(),
)

@Serializable
private data class CreateUserDto(
    val username: String,
    val displayName: String,
    val password: String,
    val grants: List<AdminGrant> = emptyList(),
)

@Serializable
private data class ResetPasswordDto(val newPassword: String)

@Serializable
private data class SetGrantsDto(val grants: List<AdminGrant>)

/**
 * Client for the super-admin user-management endpoints. Uses the shared
 * authenticated client, so a rejected token bounces the app to Login and
 * validation errors surface as exceptions with the server's message.
 *
 * Koin singleton; only the super administrator gets non-403 responses.
 */
class AdminApi(session: AuthSession) {

    private val httpClient by lazy { authenticatedJsonClient(session) }

    private val base: String get() = "${AppConfig.require().serverBaseUrl}/api/admin/users"

    suspend fun listUsers(): Result<List<AdminUser>> = runCatching {
        httpClient.get(base).body()
    }

    suspend fun createUser(
        username: String,
        displayName: String,
        password: String,
        grants: List<AdminGrant>,
    ): Result<Unit> = runCatching {
        httpClient.post(base) {
            contentType(ContentType.Application.Json)
            setBody(CreateUserDto(username, displayName, password, grants))
        }
        Unit
    }

    suspend fun resetPassword(userId: Long, newPassword: String): Result<Unit> = runCatching {
        httpClient.put("$base/$userId/password") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordDto(newPassword))
        }
        Unit
    }

    suspend fun setGrants(userId: Long, grants: List<AdminGrant>): Result<Unit> = runCatching {
        httpClient.put("$base/$userId/grants") {
            contentType(ContentType.Application.Json)
            setBody(SetGrantsDto(grants))
        }
        Unit
    }

    suspend fun deleteUser(userId: Long): Result<Unit> = runCatching {
        httpClient.delete("$base/$userId")
        Unit
    }
}
