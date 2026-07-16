package eu.anifantakis.commercials.feature.user_management.domain.data_source

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.feature.user_management.domain.*

/**
 * Network side of the feature - the only class that touches the HTTP
 * client. Same surface as the repository: the repository is the organizer,
 * this is the wire.
 */
interface RemoteUserManagementDataSource {
    suspend fun listUsers(): DataResult<List<ManagedUser>, RemoteError>
    suspend fun createUser(
        username: String,
        displayName: String,
        email: String?,
        grants: List<UserGrant>,
    ): DataResult<TempPasswordResult, RemoteError>
    suspend fun resetPassword(userId: Long): DataResult<TempPasswordResult, RemoteError>
    suspend fun setGrants(userId: Long, grants: List<UserGrant>): DataResult<Unit, RemoteError>
    suspend fun deleteUser(userId: Long): DataResult<Unit, RemoteError>
    suspend fun listAllApiTokens(): DataResult<List<AdminApiToken>, RemoteError>
    suspend fun revokeApiToken(tokenId: Long): DataResult<Unit, RemoteError>
    suspend fun reassignApiToken(workstation: String, targetUserId: Long): DataResult<Unit, RemoteError>
    suspend fun getMcpSettings(): DataResult<McpSettings, RemoteError>
    suspend fun setMcpEnabled(enabled: Boolean): DataResult<Unit, RemoteError>
}
