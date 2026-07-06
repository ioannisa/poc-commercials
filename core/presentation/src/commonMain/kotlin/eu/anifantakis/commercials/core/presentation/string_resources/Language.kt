package eu.anifantakis.commercials.core.presentation.string_resources

/**
 * The languages the app ships translations for. The default is the system
 * locale when it is one of these; otherwise it falls back to [EN] (product
 * spec). [code] is the ISO-639 language code used for system-locale matching
 * and for persisting the user's choice.
 */
enum class Language(val code: String, val displayName: String) {
    EL("el", "Ελληνικά"),
    EN("en", "English");

    companion object {
        /** Used when the system locale is none of the supported languages. */
        val FALLBACK = EN

        fun fromCode(code: String?): Language? =
            code?.lowercase()?.take(2)?.let { c -> entries.find { it.code == c } }
    }
}
