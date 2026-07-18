package eu.anifantakis.commercials.feature.auth.domain

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.feature.auth.domain.model.AiConfirmation
import eu.anifantakis.commercials.feature.auth.domain.model.ApiToken
import eu.anifantakis.commercials.feature.auth.domain.model.CreatedApiToken
import eu.anifantakis.commercials.feature.auth.domain.model.OAuthGrant
import eu.anifantakis.commercials.feature.auth.domain.model.ResetOutcome
import eu.anifantakis.commercials.feature.auth.domain.model.WorkstationAvailability

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

    /** "Forgot password" step 1: request an emailed 6-digit code (always succeeds bar a network error). */
    suspend fun forgotPassword(username: String): EmptyDataResult<AuthError>

    /** Step 2: complete the reset with the emailed code; the outcome distinguishes wrong/locked/expired. */
    suspend fun resetPassword(username: String, code: String, newPassword: String): DataResult<ResetOutcome, AuthError>

    /** The caller's own MCP/API personal access tokens (one per workstation). */
    suspend fun listApiTokens(): DataResult<List<ApiToken>, AuthError>

    /** Whether [workstation] is FREE, MINE, or held by another user (OTHER). */
    suspend fun checkWorkstation(workstation: String): DataResult<WorkstationAvailability, AuthError>

    /**
     * Mints a NON-EXPIRING token bound to [workstation]; the raw secret + MCP URL
     * come back ONCE. Replaces any existing token for that workstation - taking
     * over ANOTHER user's requires [confirmTakeover].
     */
    suspend fun createApiToken(workstation: String, confirmTakeover: Boolean): DataResult<CreatedApiToken, AuthError>

    /** Revokes one of the caller's own tokens. */
    suspend fun revokeApiToken(id: Long): EmptyDataResult<AuthError>

    /** The caller's own OAuth grants (AI clients they connected via browser login). */
    suspend fun listOAuthGrants(): DataResult<List<OAuthGrant>, AuthError>

    /** Revokes one of the caller's own OAuth grants (that connector must re-authenticate). */
    suspend fun revokeOAuthGrant(id: Long): EmptyDataResult<AuthError>

    /** The caller's "confirm new AI connections" opt-in. */
    suspend fun getAiConfirmation(): DataResult<AiConfirmation, AuthError>

    /** Sets the opt-in (the server rejects enabling without an e-mail on file). */
    suspend fun setAiConfirmation(enabled: Boolean): EmptyDataResult<AuthError>
}
