package eu.anifantakis.commercials.core.presentation.util

import eu.anifantakis.commercials.core.domain.util.RemoteError

/** Server messages pass through verbatim; transport gets the generic text. */
fun RemoteError.toDisplayMessage(): String = when (this) {
    is RemoteError.Server -> message
    is RemoteError.Transport -> error.toDisplayMessage()
}
