package eu.anifantakis.commercials.core.presentation.string_resources

import androidx.compose.ui.text.intl.Locale
import eu.anifantakis.commercials.core.presentation.string_resources.lang.De
import eu.anifantakis.commercials.core.presentation.string_resources.lang.El
import eu.anifantakis.commercials.core.presentation.string_resources.lang.En
import eu.anifantakis.commercials.core.presentation.string_resources.lang.Fr
import eu.anifantakis.commercials.core.presentation.string_resources.lang.He
import eu.anifantakis.commercials.core.presentation.string_resources.lang.It
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global holder of the active language and string resolution. PURE in-memory
 * (zero dependencies — accessible from the `localized()` extensions without a
 * Koin lookup). Persistence is NOT its job: the app seeds it at startup from the
 * one persisted KSafe entry (App.kt), and the language picker writes that entry
 * (PreferencesViewModel) — persistence stays at the edges / data layer.
 */
object LocalizationManager {

    private val providers: Map<Language, LanguageStrings> = mapOf(
        Language.EL to El(),
        Language.EN to En(),
        Language.DE to De(),
        Language.IT to It(),
        Language.FR to Fr(),
        Language.HE to He()
    )

    private val _currentLanguage = MutableStateFlow(Language.FALLBACK)
    val currentLanguage: StateFlow<Language> = _currentLanguage.asStateFlow()
    val current: Language get() = _currentLanguage.value

    fun getString(key: StringKey): String =
        (providers[_currentLanguage.value] ?: providers.getValue(Language.FALLBACK)).getString(key)

    /** Switch the active language (in-memory; caller persists). Recomposes readers. */
    fun setLanguage(language: Language) {
        _currentLanguage.value = language
    }

    fun availableLanguages(): List<Language> = providers.keys.toList()

    /**
     * The language to start in given the [savedCode] read from persistence:
     * the saved choice, else the system locale if supported, else English.
     */
    fun resolveStartup(savedCode: String): Language =
        Language.fromCode(savedCode.ifEmpty { null })   // 1) saved choice
            ?: Language.fromCode(Locale.current.language) // 2) system locale, if supported
            ?: Language.FALLBACK                          // 3) English
}
