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
        password: String,
        grants: List<UserGrant>,
    ): DataResult<Unit, RemoteError>
    suspend fun resetPassword(userId: Long, newPassword: String): DataResult<Unit, RemoteError>
    suspend fun setGrants(userId: Long, grants: List<UserGrant>): DataResult<Unit, RemoteError>
    suspend fun deleteUser(userId: Long): DataResult<Unit, RemoteError>
}
