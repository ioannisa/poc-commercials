package eu.anifantakis.commercials.feature.user_management.domain

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError

/** One user's grant on one station, as managed by the super admin. */
data class UserGrant(val stationId: String, val role: String, val clientCode: String? = null)

/** A managed account as listed by the admin endpoints. */
data class ManagedUser(
    val id: Long,
    val username: String,
    val displayName: String,
    val isAdmin: Boolean = false,
    val grants: List<UserGrant> = emptyList(),
)

/**
 * Super-admin user management: list, create, reset password, edit
 * per-station grants, delete. Only the super administrator gets non-403
 * responses; their own account lives in server.yaml, not here.
 */
interface UserManagementRepository {
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
