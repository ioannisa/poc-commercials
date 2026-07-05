package eu.anifantakis.commercials.feature.user_management.data

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.feature.user_management.domain.ManagedUser
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
        password: String,
        grants: List<UserGrant>,
    ): DataResult<Unit, RemoteError> = remoteDataSource.createUser(username, displayName, password, grants)

    override suspend fun resetPassword(userId: Long, newPassword: String): DataResult<Unit, RemoteError> =
        remoteDataSource.resetPassword(userId, newPassword)

    override suspend fun setGrants(userId: Long, grants: List<UserGrant>): DataResult<Unit, RemoteError> =
        remoteDataSource.setGrants(userId, grants)

    override suspend fun deleteUser(userId: Long): DataResult<Unit, RemoteError> =
        remoteDataSource.deleteUser(userId)
}
