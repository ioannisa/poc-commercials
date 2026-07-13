package eu.anifantakis.commercials.feature.auth.domain

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.Error

/**
 * Auth-specific failures. [Server] carries the backend's own message (the
 * server speaks Greek to the operator and its texts are authoritative);
 * transport problems ride as [Network].
 */
sealed interface AuthError : Error {
    data object InvalidCredentials : AuthError
    data object NoStationsAssigned : AuthError
    data object NotLoggedIn : AuthError

    /**
     * The credentials were RIGHT, but the session could not be written to
     * encrypted storage - so staying logged in is impossible.
     *
     * In practice this means one thing: a browser page served over plain HTTP
     * from something other than localhost. Browsers withhold `crypto.subtle`
     * outside a secure context, every encrypted write fails, and the token is
     * rolled straight back out of storage. Left unreported, the app would walk
     * into the authenticated screens with a session that had already evaporated,
     * be bounced back by the next 401, and say nothing about why.
     *
     * TERMINAL, not retryable: retrying cannot make WebCrypto appear. The fix is
     * the deployment (HTTPS, or serve from localhost), so the message has to say
     * that rather than invite another attempt.
     */
    data object SessionNotPersisted : AuthError
    data class Server(val message: String) : AuthError
    data class Network(val error: DataError.Network) : AuthError
}
