package eu.anifantakis.commercials.core.presentation.util

import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.presentation.helper.UiText

/**
 * Server messages pass through VERBATIM ([UiText.Dynamic] — the backend's text
 * is authoritative, never translated); transport failures get the localized
 * generic text.
 */
fun RemoteError.toUiText(): UiText = when (this) {
    is RemoteError.Server -> UiText.Dynamic(message)
    is RemoteError.Transport -> error.toUiText()
}
