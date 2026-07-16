package eu.anifantakis.commercials.feature.auth.presentation

import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.feature.auth.domain.AuthError

/**
 * Auth failures as [UiText]: localized keys for the client-side cases,
 * verbatim pass-through ([UiText.Dynamic]) for the backend's own
 * authoritative [AuthError.Server] message.
 */
fun AuthError.toUiText(): UiText = when (this) {
    AuthError.InvalidCredentials -> UiText.Res(StringKey.AUTH_INVALID_CREDENTIALS)
    AuthError.NoStationsAssigned -> UiText.Res(StringKey.AUTH_NO_STATIONS_ASSIGNED)
    AuthError.NotLoggedIn -> UiText.Res(StringKey.AUTH_NOT_LOGGED_IN)
    // Terminal: retrying cannot conjure WebCrypto. The message names the fix.
    AuthError.SessionNotPersisted -> UiText.Res(StringKey.AUTH_SESSION_NOT_PERSISTED)
    // Handled inline by the token dialog (turned into a takeover prompt); the
    // generic text is a fallback if it ever surfaces as a plain error.
    AuthError.Conflict -> UiText.Res(StringKey.MCP_TOKENS_TAKEOVER_NEEDED)
    is AuthError.Server -> UiText.Dynamic(message)
    is AuthError.Network -> UiText.Res(StringKey.AUTH_NETWORK_UNREACHABLE)
}
