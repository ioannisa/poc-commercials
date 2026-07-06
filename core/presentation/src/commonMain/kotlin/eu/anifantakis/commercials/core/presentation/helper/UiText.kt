package eu.anifantakis.commercials.core.presentation.helper

import androidx.compose.runtime.Composable
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.string_resources.localizedCompose
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs

/**
 * A piece of user-facing text that is EITHER a raw runtime string (from the
 * server, user input, a formatted number) OR a reference to a localized string
 * (+ positional args), resolved at the UI edge in the current app language.
 *
 * The FAÇADE over the localization backend: screens and ViewModels depend on
 * `UiText`, never on the backend. This project backs it with the hand-rolled
 * `StringKey` + `LocalizationManager` (in-app language switching, works
 * off-Compose, server-key friendly). Swapping to Compose Multiplatform
 * Resources later would touch ONLY this file — every `text.asString()` call
 * site is untouched.
 *
 * Lives in :core:presentation; it must NOT leak into :domain. Domain-produced
 * text stays a bare [StringKey], resolved in the UI.
 */
sealed interface UiText {

    /** A literal string decided at runtime (server payload, user input, …). */
    data class Dynamic(val value: String) : UiText

    /** A localized [StringKey] with optional positional args (`{0}`, `{1}`, …). */
    data class Res(val key: StringKey, val args: List<Any> = emptyList()) : UiText

    /**
     * Resolve inside composition — reads the active language, so the text
     * updates automatically when the user switches language.
     */
    @Composable
    fun asString(): String = when (this) {
        is Dynamic -> value
        is Res -> key.localizedCompose().withArgs(args)
    }

    /**
     * Resolve outside composition (a log line, a share payload, a value stored
     * in ViewModel state). Synchronous — `StringKey` resolution is a map lookup.
     */
    fun resolve(): String = when (this) {
        is Dynamic -> value
        is Res -> key.localized().withArgs(args)
    }

    companion object {
        fun res(key: StringKey, vararg args: Any): UiText = Res(key, args.toList())
    }
}
