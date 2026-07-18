package eu.anifantakis.commercials.feature.user_management.data.data_source

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.domain.util.map
import eu.anifantakis.commercials.feature.user_management.domain.AdminApiToken
import eu.anifantakis.commercials.feature.user_management.domain.AdminOAuthToken
import eu.anifantakis.commercials.feature.user_management.domain.ManagedUser
import eu.anifantakis.commercials.feature.user_management.domain.McpSettings
import eu.anifantakis.commercials.feature.user_management.domain.TempPasswordResult
import eu.anifantakis.commercials.feature.user_management.domain.UserGrant
import eu.anifantakis.commercials.feature.user_management.domain.data_source.RemoteUserManagementDataSource
import kotlinx.serialization.Serializable

@Serializable
private data class GrantDto(val stationId: String, val role: String, val clientCode: String? = null)

@Serializable
private data class UserDto(
    val id: Long,
    val username: String,
    val displayName: String,
    val email: String? = null,
    val isAdmin: Boolean = false,
    val grants: List<GrantDto> = emptyList(),
)

@Serializable
private data class CreateUserDto(
    val username: String,
    val displayName: String,
    val email: String? = null,
    val grants: List<GrantDto> = emptyList(),
)

/** The server's create/reset reply: the one-time temp password. */
@Serializable
private data class TempPasswordResponseDto(val id: Long, val tempPassword: String, val emailSent: Boolean)

@Serializable
private data class AdminApiTokenDto(
    val id: Long,
    val workstationName: String,
    val userId: Long,
    val username: String,
    val userRole: String,
    val createdAt: String,
    val lastUsedAt: String? = null,
)

@Serializable
private data class ReassignApiTokenDto(val workstation: String, val targetUserId: Long)

@Serializable
private data class AdminOAuthTokenDto(
    val id: Long,
    val userId: Long,
    val username: String,
    val clientName: String,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val refreshExpiresAt: String,
)

@Serializable
private data class McpSettingsDto(val enabled: Boolean, val tokenCount: Int, val oauthGrantCount: Int = 0)

@Serializable
private data class SetMcpEnabledDto(val enabled: Boolean)

private fun AdminApiTokenDto.toDomain() =
    AdminApiToken(id, workstationName, userId, username, userRole, createdAt, lastUsedAt)

private fun AdminOAuthTokenDto.toDomain() =
    AdminOAuthToken(id, userId, username, clientName, createdAt, lastUsedAt)

@Serializable
private data class SetGrantsDto(val grants: List<GrantDto>)

private fun GrantDto.toDomain() = UserGrant(stationId, role, clientCode)
private fun UserGrant.toDto() = GrantDto(stationId, role, clientCode)
private fun UserDto.toDomain() = ManagedUser(id, username, displayName, email, isAdmin, grants.map { it.toDomain() })
private fun TempPasswordResponseDto.toDomain() = TempPasswordResult(tempPassword, emailSent)

class RemoteUserManagementDataSourceImpl(private val api: ApiHttpClient) : RemoteUserManagementDataSource {

    override suspend fun listUsers(): DataResult<List<ManagedUser>, RemoteError> =
        api.getRemote<List<UserDto>>("/api/admin/users")
            .map { list -> list.map { it.toDomain() } }

    override suspend fun createUser(
        username: String,
        displayName: String,
        email: String?,
        grants: List<UserGrant>,
    ): DataResult<TempPasswordResult, RemoteError> =
        api.postRemote<CreateUserDto, TempPasswordResponseDto>(
            "/api/admin/users",
            CreateUserDto(username, displayName, email, grants.map { it.toDto() }),
        ).map { it.toDomain() }

    override suspend fun resetPassword(userId: Long): DataResult<TempPasswordResult, RemoteError> =
        api.postRemote<Unit, TempPasswordResponseDto>("/api/admin/users/$userId/reset").map { it.toDomain() }

    override suspend fun setGrants(userId: Long, grants: List<UserGrant>): DataResult<Unit, RemoteError> =
        api.putRemoteEmpty("/api/admin/users/$userId/grants", SetGrantsDto(grants.map { it.toDto() }))

    override suspend fun deleteUser(userId: Long): DataResult<Unit, RemoteError> =
        api.deleteRemoteEmpty("/api/admin/users/$userId")

    override suspend fun listAllApiTokens(): DataResult<List<AdminApiToken>, RemoteError> =
        api.getRemote<List<AdminApiTokenDto>>("/api/admin/api-tokens").map { list -> list.map { it.toDomain() } }

    override suspend fun revokeApiToken(tokenId: Long): DataResult<Unit, RemoteError> =
        api.deleteRemoteEmpty("/api/admin/api-tokens/$tokenId")

    override suspend fun listAllOAuthTokens(): DataResult<List<AdminOAuthToken>, RemoteError> =
        api.getRemote<List<AdminOAuthTokenDto>>("/api/admin/oauth-tokens").map { list -> list.map { it.toDomain() } }

    override suspend fun revokeOAuthToken(tokenId: Long): DataResult<Unit, RemoteError> =
        api.deleteRemoteEmpty("/api/admin/oauth-tokens/$tokenId")

    override suspend fun reassignApiToken(workstation: String, targetUserId: Long): DataResult<Unit, RemoteError> =
        api.postRemoteEmpty("/api/admin/api-tokens/reassign", ReassignApiTokenDto(workstation, targetUserId))

    override suspend fun getMcpSettings(): DataResult<McpSettings, RemoteError> =
        api.getRemote<McpSettingsDto>("/api/admin/mcp-settings")
            .map { McpSettings(it.enabled, it.tokenCount, it.oauthGrantCount) }

    override suspend fun setMcpEnabled(enabled: Boolean): DataResult<Unit, RemoteError> =
        api.putRemoteEmpty("/api/admin/mcp-settings", SetMcpEnabledDto(enabled))
}
