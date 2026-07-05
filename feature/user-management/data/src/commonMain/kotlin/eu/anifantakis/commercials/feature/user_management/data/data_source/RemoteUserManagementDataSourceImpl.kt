package eu.anifantakis.commercials.feature.user_management.data.data_source

import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.core.data.network.authenticatedJsonClient
import eu.anifantakis.commercials.core.data.network.remoteCall
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.domain.util.map
import eu.anifantakis.commercials.feature.user_management.domain.ManagedUser
import eu.anifantakis.commercials.feature.user_management.domain.UserGrant
import eu.anifantakis.commercials.feature.user_management.domain.data_source.RemoteUserManagementDataSource
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
private data class GrantDto(val stationId: String, val role: String, val clientCode: String? = null)

@Serializable
private data class UserDto(
    val id: Long,
    val username: String,
    val displayName: String,
    val isAdmin: Boolean = false,
    val grants: List<GrantDto> = emptyList(),
)

@Serializable
private data class CreateUserDto(
    val username: String,
    val displayName: String,
    val password: String,
    val grants: List<GrantDto> = emptyList(),
)

@Serializable
private data class ResetPasswordDto(val newPassword: String)

@Serializable
private data class SetGrantsDto(val grants: List<GrantDto>)

private fun GrantDto.toDomain() = UserGrant(stationId, role, clientCode)
private fun UserGrant.toDto() = GrantDto(stationId, role, clientCode)
private fun UserDto.toDomain() = ManagedUser(id, username, displayName, isAdmin, grants.map { it.toDomain() })

class RemoteUserManagementDataSourceImpl(private val session: AuthSession) : RemoteUserManagementDataSource {

    private val httpClient by lazy { authenticatedJsonClient(session) }

    private val base: String get() = "${AppConfig.require().serverBaseUrl}/api/admin/users"

    override suspend fun listUsers(): DataResult<List<ManagedUser>, RemoteError> = remoteCall {
        httpClient.get(base).body<List<UserDto>>()
    }.map { list -> list.map { it.toDomain() } }

    override suspend fun createUser(
        username: String,
        displayName: String,
        password: String,
        grants: List<UserGrant>,
    ): DataResult<Unit, RemoteError> = remoteCall {
        httpClient.post(base) {
            contentType(ContentType.Application.Json)
            setBody(CreateUserDto(username, displayName, password, grants.map { it.toDto() }))
        }
        Unit
    }

    override suspend fun resetPassword(userId: Long, newPassword: String): DataResult<Unit, RemoteError> = remoteCall {
        httpClient.put("$base/$userId/password") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordDto(newPassword))
        }
        Unit
    }

    override suspend fun setGrants(userId: Long, grants: List<UserGrant>): DataResult<Unit, RemoteError> = remoteCall {
        httpClient.put("$base/$userId/grants") {
            contentType(ContentType.Application.Json)
            setBody(SetGrantsDto(grants.map { it.toDto() }))
        }
        Unit
    }

    override suspend fun deleteUser(userId: Long): DataResult<Unit, RemoteError> = remoteCall {
        httpClient.delete("$base/$userId")
        Unit
    }
}
