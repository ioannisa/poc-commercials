package eu.anifantakis.commercials.feature.auth.presentation

import eu.anifantakis.commercials.feature.auth.domain.AuthError

/**
 * Auth failures to operator-facing text. Plain String instead of the
 * skill's StringKey - this POC is deliberately mono-lingual, and
 * [AuthError.Server] carries the backend's own authoritative message.
 */
fun AuthError.toDisplayMessage(): String = when (this) {
    AuthError.InvalidCredentials -> "Invalid username or password"
    AuthError.NoStationsAssigned -> "No stations are assigned to this account"
    AuthError.NotLoggedIn -> "Not logged in"
    is AuthError.Server -> message
    is AuthError.Network -> "Could not reach the server"
}
