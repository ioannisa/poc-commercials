package eu.anifantakis.commercials.core.presentation.helper

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * A piece of user-facing text that is EITHER a raw runtime string (from the
 * server, user input, a formatted number) OR a reference to a localized
 * resource (+ format args), resolved at the UI edge in the viewer's locale.
 *
 * This is the KMP form of the classic `UiText` pattern: it uses Compose
 * Multiplatform Resources ([StringResource] + [stringResource]/[getString]),
 * NOT Android's `Context`/`Int`/`getString`, so it lives in commonMain. It lets
 * ViewModels emit text without knowing the language or touching the platform -
 * the composable resolves it, so switching locale re-renders automatically.
 *
 * Lives in :core:presentation (Compose is required for [StringResource]); it
 * must NOT leak into :domain. Domain-produced text stays a plain-Kotlin key.
 */
sealed interface UiText {

    /** A literal string decided at runtime (server payload, user input, …). */
    data class Dynamic(val value: String) : UiText

    /** A localized resource (`Res.string.*`) with optional format args. */
    data class Res(
        val id: StringResource,
        val args: List<Any> = emptyList(),
    ) : UiText

    /**
     * Resolve inside composition — reads the current locale, so the text
     * updates automatically when the language changes.
     */
    @Composable
    fun asString(): String = when (this) {
        is Dynamic -> value
        is Res -> stringResource(id, *args.toTypedArray())
    }

    /**
     * Resolve outside composition (building a notification, a log line, a
     * share-sheet payload). Suspends because resource loading is async.
     */
    suspend fun resolve(): String = when (this) {
        is Dynamic -> value
        is Res -> getString(id, *args.toTypedArray())
    }

    companion object {
        /** Convenience for the common no-args resource case. */
        fun res(id: StringResource): UiText = Res(id)
    }
}
