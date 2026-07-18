package eu.anifantakis.commercials.feature.auth.domain.data_source

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.feature.auth.domain.AuthError
import eu.anifantakis.commercials.feature.auth.domain.model.AiConfirmation
import eu.anifantakis.commercials.feature.auth.domain.model.ApiToken
import eu.anifantakis.commercials.feature.auth.domain.model.CreatedApiToken
import eu.anifantakis.commercials.feature.auth.domain.model.LoginResult
import eu.anifantakis.commercials.feature.auth.domain.model.OAuthGrant
import eu.anifantakis.commercials.feature.auth.domain.model.ResetOutcome
import eu.anifantakis.commercials.feature.auth.domain.model.WorkstationAvailability

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

    /** Requests an emailed 6-digit reset code. */
    suspend fun forgotPassword(username: String): EmptyDataResult<AuthError>

    /** Completes the reset with the emailed code; the [ResetOutcome] distinguishes wrong/locked/expired. */
    suspend fun resetPassword(
        username: String,
        code: String,
        newPassword: String,
    ): DataResult<ResetOutcome, AuthError>

    suspend fun listApiTokens(token: String): DataResult<List<ApiToken>, AuthError>

    suspend fun checkWorkstation(token: String, workstation: String): DataResult<WorkstationAvailability, AuthError>

    suspend fun createApiToken(
        token: String,
        workstation: String,
        confirmTakeover: Boolean,
    ): DataResult<CreatedApiToken, AuthError>

    suspend fun revokeApiToken(token: String, id: Long): EmptyDataResult<AuthError>

    /** The caller's own OAuth grants (connected AI clients). */
    suspend fun listOAuthGrants(token: String): DataResult<List<OAuthGrant>, AuthError>

    /** Revoke one of the caller's own OAuth grants by id. */
    suspend fun revokeOAuthGrant(token: String, id: Long): EmptyDataResult<AuthError>

    suspend fun getAiConfirmation(token: String): DataResult<AiConfirmation, AuthError>

    suspend fun setAiConfirmation(token: String, enabled: Boolean): EmptyDataResult<AuthError>
}
