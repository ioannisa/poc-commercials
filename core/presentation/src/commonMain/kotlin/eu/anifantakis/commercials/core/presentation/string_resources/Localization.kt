package eu.anifantakis.commercials.core.presentation.string_resources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue

/**
 * Provided at the app root by [LocalizationProvider]. Reading it in a composable
 * subscribes that composable to language changes (the actual text comes from
 * [LocalizationManager]; this is purely the recomposition trigger).
 */
val LocalLanguage: ProvidableCompositionLocal<Language?> = compositionLocalOf { Language.FALLBACK }

/** The app-wide composable accessor: `Strings[StringKey.X]` (recomposes on switch). */
object Strings {
    @Composable
    @ReadOnlyComposable
    operator fun get(key: StringKey): String {
        LocalLanguage.current                         // subscribe to language changes
        return LocalizationManager.getString(key)
    }
}

/** Non-composable resolve (ViewModels, error mappers). */
fun StringKey.localized(): String = LocalizationManager.getString(this)

/** Composable resolve that recomposes on a language switch. */
@Composable
@ReadOnlyComposable
fun StringKey.localizedCompose(): String {
    LocalLanguage.current
    return LocalizationManager.getString(this)
}

/**
 * Resolve a raw server wire-name to its localized string. Unknown wire-names
 * resolve to UNMATCHED → "" → the raw string shows through — so you can
 * scaffold UI with backend keys as placeholders BEFORE the strings exist, then
 * add the key/translations later with no call-site change (golden-standard
 * behavior).
 */
fun String.localized(): String {
    val localized = LocalizationManager.getString(StringKey.fromJson(this))
    return localized.ifEmpty { this }
}

/** Positional args into a resolved string: `{0}`, `{1}`, … */
fun String.withArgs(args: List<Any>): String =
    args.foldIndexed(this) { i, acc, a -> acc.replace("{$i}", a.toString()) }

/** Wraps the app so every `Strings[...]` recomposes when the language switches. */
@Composable
fun LocalizationProvider(content: @Composable () -> Unit) {
    val language by LocalizationManager.currentLanguage.collectAsState()
    CompositionLocalProvider(LocalLanguage provides language, content = content)
}

/** Current language as Compose state (for the language picker's selection). */
@Composable
fun rememberCurrentLanguage(): State<Language> =
    LocalizationManager.currentLanguage.collectAsState()
