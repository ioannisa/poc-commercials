package eu.anifantakis.commercials.feature.auth.domain

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult

/**
 * Authentication operations. Implementations own the session persistence:
 * a successful [login] stores the encrypted session (token, stations,
 * roles); [logout] and a successful [changePassword] clear it.
 */
interface AuthRepository {

    suspend fun login(username: String, password: String): EmptyDataResult<AuthError>

    /** Best-effort server-side token revoke, then local session clear. */
    suspend fun logout()

    /** On success the server revokes ALL sessions; the local one is cleared. */
    suspend fun changePassword(currentPassword: String, newPassword: String): EmptyDataResult<AuthError>

    /** "Forgot password": consumes ONE recovery code to set a new password. */
    suspend fun recoverPassword(username: String, recoveryCode: String, newPassword: String): EmptyDataResult<AuthError>

    /** Regenerates one-time recovery codes; raw codes are shown ONCE. */
    suspend fun regenerateRecoveryCodes(): DataResult<List<String>, AuthError>
}
