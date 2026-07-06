package eu.anifantakis.commercials.core.presentation.string_resources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import eu.anifantakis.commercials.core.presentation.string_resources.lang.EL_STRINGS
import eu.anifantakis.commercials.core.presentation.string_resources.lang.EN_STRINGS
import org.koin.mp.KoinPlatform

/**
 * The active UI language provided at the app root
 * (`CompositionLocalProvider(LocalLanguage provides manager.current)`). Reading
 * it in a composable recomposes that composable when the language switches.
 */
val LocalLanguage = staticCompositionLocalOf { Language.FALLBACK }

/** Pure lookup over the static maps: chosen language → English → the key name. */
internal fun resolve(key: StringKey, language: Language): String =
    (if (language == Language.EL) EL_STRINGS else EN_STRINGS)[key]
        ?: EN_STRINGS[key]
        ?: key.name

/**
 * Composable string reads — the app-wide accessor. `Strings[StringKey.X]`
 * recomposes on a language switch (it reads [LocalLanguage]).
 */
object Strings {
    @Composable
    operator fun get(key: StringKey): String = resolve(key, LocalLanguage.current)
}

/**
 * Non-composable read (ViewModels, error mappers) — resolves in the CURRENT app
 * language via the [LocalizationManager] singleton. A snapshot: a value already
 * stored in state won't re-translate on a later switch (fine for transient text).
 */
fun StringKey.localized(): String =
    resolve(this, KoinPlatform.getKoin().get<LocalizationManager>().current)

/** Composable read that subscribes to language changes (equivalent to `Strings[this]`). */
@Composable
fun StringKey.localizedCompose(): String = resolve(this, LocalLanguage.current)

/** Apply positional args to a resolved string: `{0}`, `{1}`, … */
fun String.withArgs(args: List<Any>): String =
    args.foldIndexed(this) { i, acc, a -> acc.replace("{$i}", a.toString()) }
