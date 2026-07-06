package eu.anifantakis.commercials.feature.auth.presentation

import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.feature.auth.domain.AuthError

/**
 * Auth failures to operator-facing text, localized via StringKey.
 * [AuthError.Server] passes through the backend's own authoritative message.
 */
fun AuthError.toDisplayMessage(): String = when (this) {
    AuthError.InvalidCredentials -> StringKey.AUTH_INVALID_CREDENTIALS.localized()
    AuthError.NoStationsAssigned -> StringKey.AUTH_NO_STATIONS_ASSIGNED.localized()
    AuthError.NotLoggedIn -> StringKey.AUTH_NOT_LOGGED_IN.localized()
    is AuthError.Server -> message
    is AuthError.Network -> StringKey.AUTH_NETWORK_UNREACHABLE.localized()
}
