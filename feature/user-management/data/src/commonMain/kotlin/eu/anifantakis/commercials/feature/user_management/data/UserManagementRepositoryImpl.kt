package eu.anifantakis.commercials.feature.user_management.data

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.feature.user_management.domain.AdminApiToken
import eu.anifantakis.commercials.feature.user_management.domain.ManagedUser
import eu.anifantakis.commercials.feature.user_management.domain.McpSettings
import eu.anifantakis.commercials.feature.user_management.domain.TempPasswordResult
import eu.anifantakis.commercials.feature.user_management.domain.UserGrant
import eu.anifantakis.commercials.feature.user_management.domain.UserManagementRepository
import eu.anifantakis.commercials.feature.user_management.domain.data_source.RemoteUserManagementDataSource

/** Organizer only: all I/O lives in [RemoteUserManagementDataSource]. */
class UserManagementRepositoryImpl(
    private val remoteDataSource: RemoteUserManagementDataSource,
) : UserManagementRepository {

    override suspend fun listUsers(): DataResult<List<ManagedUser>, RemoteError> =
        remoteDataSource.listUsers()

    override suspend fun createUser(
        username: String,
        displayName: String,
        email: String?,
        grants: List<UserGrant>,
    ): DataResult<TempPasswordResult, RemoteError> = remoteDataSource.createUser(username, displayName, email, grants)

    override suspend fun resetPassword(userId: Long): DataResult<TempPasswordResult, RemoteError> =
        remoteDataSource.resetPassword(userId)

    override suspend fun setGrants(userId: Long, grants: List<UserGrant>): DataResult<Unit, RemoteError> =
        remoteDataSource.setGrants(userId, grants)

    override suspend fun deleteUser(userId: Long): DataResult<Unit, RemoteError> =
        remoteDataSource.deleteUser(userId)

    override suspend fun listAllApiTokens(): DataResult<List<AdminApiToken>, RemoteError> =
        remoteDataSource.listAllApiTokens()

    override suspend fun revokeApiToken(tokenId: Long): DataResult<Unit, RemoteError> =
        remoteDataSource.revokeApiToken(tokenId)

    override suspend fun reassignApiToken(workstation: String, targetUserId: Long): DataResult<Unit, RemoteError> =
        remoteDataSource.reassignApiToken(workstation, targetUserId)

    override suspend fun getMcpSettings(): DataResult<McpSettings, RemoteError> =
        remoteDataSource.getMcpSettings()

    override suspend fun setMcpEnabled(enabled: Boolean): DataResult<Unit, RemoteError> =
        remoteDataSource.setMcpEnabled(enabled)
}
