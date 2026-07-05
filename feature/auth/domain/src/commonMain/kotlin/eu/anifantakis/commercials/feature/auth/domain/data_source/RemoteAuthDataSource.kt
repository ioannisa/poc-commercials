package eu.anifantakis.commercials.feature.auth.domain.data_source

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.feature.auth.domain.AuthError
import eu.anifantakis.commercials.feature.auth.domain.model.LoginResult

/**
 * Network side of /api/auth - the only class that touches the HTTP client.
 * Pure wire calls: session policy (what a login MEANS, when the session is
 * cleared) lives in the repository.
 */
interface RemoteAuthDataSource {

    suspend fun login(username: String, password: String): DataResult<LoginResult, AuthError>

    /** Best-effort server-side revocation; network failures are swallowed. */
    suspend fun logout(token: String)

    suspend fun changePassword(
        token: String,
        currentPassword: String,
        newPassword: String,
    ): EmptyDataResult<AuthError>

    suspend fun recoverPassword(
        username: String,
        recoveryCode: String,
        newPassword: String,
    ): EmptyDataResult<AuthError>

    suspend fun regenerateRecoveryCodes(token: String): DataResult<List<String>, AuthError>
}
