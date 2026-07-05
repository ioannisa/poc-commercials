package eu.anifantakis.commercials.feature.auth.data

import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.data.session.StationAccess
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.core.domain.util.onSuccess
import eu.anifantakis.commercials.feature.auth.domain.AuthError
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.domain.data_source.RemoteAuthDataSource

/**
 * Organizer: OWNS the session lifecycle policy (when a login counts, when
 * the session is stored/cleared) and delegates every wire call to
 * [RemoteAuthDataSource].
 */
class AuthRepositoryImpl(
    private val remoteDataSource: RemoteAuthDataSource,
    private val session: AuthSession,
) : AuthRepository {

    override suspend fun login(username: String, password: String): EmptyDataResult<AuthError> =
        when (val result = remoteDataSource.login(username, password)) {
            is DataResult.Failure -> result
            is DataResult.Success -> {
                val login = result.data
                // The super admin may log in with zero hosted stations (user
                // management works regardless); everyone else needs at least
                // one grant to have anything to see.
                if (login.stations.isEmpty() && !login.isAdmin) {
                    DataResult.Failure(AuthError.NoStationsAssigned)
                } else {
                    session.store(
                        token = login.token,
                        displayName = login.displayName,
                        isAdmin = login.isAdmin,
                        stations = login.stations.map {
                            StationAccess(it.id, it.name, it.role, it.clientCode)
                        },
                    )
                    DataResult.Success(Unit)
                }
            }
        }

    override suspend fun logout() {
        session.token?.let { remoteDataSource.logout(it) }   // best-effort revocation
        session.clear()
    }

    override suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ): EmptyDataResult<AuthError> {
        val token = session.token ?: return DataResult.Failure(AuthError.NotLoggedIn)
        return remoteDataSource.changePassword(token, currentPassword, newPassword)
            .onSuccess {
                // Server revoked every session for this user - drop ours too
                session.clear()
            }
    }

    override suspend fun recoverPassword(
        username: String,
        recoveryCode: String,
        newPassword: String,
    ): EmptyDataResult<AuthError> =
        remoteDataSource.recoverPassword(username, recoveryCode, newPassword)

    override suspend fun regenerateRecoveryCodes(): DataResult<List<String>, AuthError> {
        val token = session.token ?: return DataResult.Failure(AuthError.NotLoggedIn)
        return remoteDataSource.regenerateRecoveryCodes(token)
    }
}
