package eu.anifantakis.commercials.core.domain.util

/**
 * Marker for every typed failure that can ride in a [DataResult]. Feature
 * validation enums implement this directly; transport/storage failures use
 * [DataError].
 */
interface Error

sealed interface DataError : Error {

    enum class Network : DataError {
        // 400-family - specific
        REQUEST_TIMEOUT, BAD_REQUEST, NOT_FOUND, METHOD_NOT_ALLOWED,
        NOT_ACCEPTABLE, PROXY_AUTHENTICATION_REQUIRED, FORBIDDEN,
        UNAUTHORIZED, CONFLICT, GONE, TOO_MANY_REQUESTS, PAYLOAD_TOO_LARGE,

        // 500-family - specific
        INTERNAL_SERVER_ERROR, NOT_IMPLEMENTED, BAD_GATEWAY,
        SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT,

        // generic fallbacks per family
        CLIENT_ERROR, SERVER_ERROR,

        // non-HTTP
        NO_INTERNET, EMPTY_CONTENT, SERIALIZATION, UNKNOWN,
    }

    enum class Local : DataError {
        DISK_FULL,
        UNKNOWN,
    }
}
