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
    data class Server(val message: String) : AuthError
    data class Network(val error: DataError.Network) : AuthError
}
