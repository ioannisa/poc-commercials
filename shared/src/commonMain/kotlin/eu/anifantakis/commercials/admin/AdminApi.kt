package eu.anifantakis.commercials.admin

import eu.anifantakis.commercials.auth.AuthSession
import eu.anifantakis.commercials.auth.authenticatedJsonClient
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

/** One hosted station database, as listed on the Databases screen. */
@Serializable
data class HostedStation(
    val id: String,
    val name: String,
    val database: String,
    val reachable: Boolean = true,
    val placements: Long? = null,
    val dateRange: String? = null,
)

@Serializable
private data class DeleteStationDto(val mode: String, val confirmId: String)

@Serializable
data class DeleteStationResult(
    val status: String = "",
    val grantsRemoved: Int = 0,
    val yamlEntryRemoved: Boolean = false,
    val databaseDropped: Boolean = false,
)

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

    private val stationsBase: String get() = "${AppConfig.require().serverBaseUrl}/api/admin/stations"

    suspend fun listStations(): Result<List<HostedStation>> = runCatching {
        httpClient.get(stationsBase).body()
    }

    /**
     * mode "safe": unhost only (yaml entry, user grants, live registry).
     * mode "hard": additionally DROP the schema on its MySQL server.
     * [confirmId] must repeat the station id - typed confirmation.
     */
    suspend fun deleteStation(id: String, mode: String, confirmId: String): Result<DeleteStationResult> = runCatching {
        httpClient.post("$stationsBase/$id/delete") {
            contentType(ContentType.Application.Json)
            setBody(DeleteStationDto(mode, confirmId))
        }.body()
    }
}
