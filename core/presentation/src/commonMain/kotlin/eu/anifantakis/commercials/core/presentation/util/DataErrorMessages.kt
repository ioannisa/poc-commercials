package eu.anifantakis.commercials.core.presentation.util

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs

/**
 * Operator-facing text for transport/storage failures, localized in the current
 * app language (StringKey + LocalizationManager). Resolved at throw-time — a
 * transient error keeps the language it was raised in.
 */
fun DataError.toDisplayMessage(): String = when (this) {
    DataError.Network.NO_INTERNET -> StringKey.ERROR_NO_INTERNET.localized()
    DataError.Network.UNAUTHORIZED -> StringKey.ERROR_SESSION_EXPIRED.localized()
    DataError.Network.FORBIDDEN -> StringKey.ERROR_FORBIDDEN.localized()
    DataError.Network.NOT_FOUND -> StringKey.ERROR_NOT_FOUND.localized()
    DataError.Network.CONFLICT -> StringKey.ERROR_CONFLICT_REFRESH.localized()
    DataError.Network.SERIALIZATION -> StringKey.ERROR_SERIALIZATION.localized()
    is DataError.Network -> StringKey.ERROR_SERVER.localized().withArgs(listOf(name))
    is DataError.Local -> StringKey.ERROR_LOCAL_STORAGE.localized()
}
