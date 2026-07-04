package eu.anifantakis.commercials.core.domain.util

/**
 * Generic remote failure for features whose server speaks authoritative
 * operator text: [Server] carries the backend's own {"error": ...} message
 * verbatim, transport problems ride as [Transport]. Features needing richer
 * cases (auth) define their own type instead.
 */
sealed interface RemoteError : Error {
    data class Server(val message: String) : RemoteError
    data class Transport(val error: DataError.Network) : RemoteError
}
