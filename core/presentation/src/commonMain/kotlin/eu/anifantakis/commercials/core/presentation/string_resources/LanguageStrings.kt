package eu.anifantakis.commercials.core.presentation.string_resources

/**
 * One language's translations. Each supported language contributes a provider
 * (`lang/El`, `lang/En`) registered in [LocalizationManager]. Implementations
 * use an EXHAUSTIVE `when (key)` (no else) — adding a [StringKey] without
 * translating it in every language is a COMPILE error, so the compiler itself
 * guards translation completeness. [StringKey.UNMATCHED] maps to "" (callers
 * like `String.localized()` fall back to the raw wire-name on empty).
 */
interface LanguageStrings {
    fun getString(key: StringKey): String
}
