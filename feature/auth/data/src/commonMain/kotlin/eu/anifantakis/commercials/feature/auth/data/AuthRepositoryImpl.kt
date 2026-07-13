package eu.anifantakis.commercials.feature.auth.data

import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.domain.auth.StationAccess
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.core.domain.util.onSuccess
import eu.anifantakis.commercials.feature.auth.domain.AuthError
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.domain.data_source.RemoteAuthDataSource
import kotlinx.coroutines.CancellationException

/**
 * Organizer: OWNS the session lifecycle policy (when a login counts, when
 * the session is stored/cleared) and delegates every wire call to
 * [RemoteAuthDataSource].
 */
class AuthRepositoryImpl(
    private val remoteDataSource: RemoteAuthDataSource,
    private val session: AuthSession,
) : AuthRepository {

    override suspend fun login(username: String, password: String): EmptyDataResult<AuthError> {
        // PREFLIGHT (the UX gate). KSafe says up front when encrypted storage is
        // broken - on a browser page that is not a secure context, WebCrypto is
        // simply absent - so refuse BEFORE sending someone's password to the
        // server, and give the real reason instead of a 401 they cannot explain.
        //
        // Not the safety net: the awaited store() below is, and it stays. This
        // only spares a round trip and makes the failure legible.
        if (session.encryptionUnavailable) {
            return DataResult.Failure(AuthError.SessionNotPersisted)
        }
        return when (val result = remoteDataSource.login(username, password)) {
            is DataResult.Failure -> result
            is DataResult.Success -> {
                val login = result.data
                // The super admin may log in with zero hosted stations (user
                // management works regardless); everyone else needs at least
                // one grant to have anything to see.
                if (login.stations.isEmpty() && !login.isAdmin) {
                    DataResult.Failure(AuthError.NoStationsAssigned)
                } else {
                    // AWAITED. store() throws when the session cannot be written,
                    // which on a non-secure browser page it cannot - and KSafe then
                    // rolls the value back, so an unreported failure would take us
                    // into the app with a session that is already gone. Fail here,
                    // visibly, instead of a 401 loop the user cannot explain.
                    //
                    // Not verified by reading the value back: a failed write can
                    // still read as present. The exception is the only honest signal.
                    try {
                        session.store(
                            token = login.token,
                            displayName = login.displayName,
                            isAdmin = login.isAdmin,
                            stations = login.stations.map {
                                StationAccess(it.id, it.name, it.role, it.clientCode)
                            },
                        )
                        DataResult.Success(Unit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Whatever was optimistically set is already rolled back by
                        // KSafe; clear() only makes the in-memory state say so too.
                        session.clear()
                        DataResult.Failure(AuthError.SessionNotPersisted)
                    }
                }
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
