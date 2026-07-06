package eu.anifantakis.commercials.core.presentation.util

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey

/**
 * Operator-facing text for transport/storage failures as a [UiText] — carried
 * unresolved (golden-standard rule) so the UI edge resolves it in the language
 * active at DISPLAY time, and a language switch re-renders a shown error.
 */
fun DataError.toUiText(): UiText = when (this) {
    DataError.Network.NO_INTERNET -> UiText.Res(StringKey.ERROR_NO_INTERNET)
    DataError.Network.UNAUTHORIZED -> UiText.Res(StringKey.ERROR_SESSION_EXPIRED)
    DataError.Network.FORBIDDEN -> UiText.Res(StringKey.ERROR_FORBIDDEN)
    DataError.Network.NOT_FOUND -> UiText.Res(StringKey.ERROR_NOT_FOUND)
    DataError.Network.CONFLICT -> UiText.Res(StringKey.ERROR_CONFLICT_REFRESH)
    DataError.Network.SERIALIZATION -> UiText.Res(StringKey.ERROR_SERIALIZATION)
    is DataError.Network -> UiText.Res(StringKey.ERROR_SERVER, listOf(name))
    is DataError.Local -> UiText.Res(StringKey.ERROR_LOCAL_STORAGE)
}
