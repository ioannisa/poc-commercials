package eu.anifantakis.commercials.core.data.preferences

import eu.anifantakis.commercials.core.domain.preferences.AppLanguageStore
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.invoke

/** KSafe-backed [AppLanguageStore] — the user's language choice, persisted plain. */
class KSafeAppLanguageStore(ksafe: KSafe) : AppLanguageStore {
    override var languageCode: String by ksafe("", key = "app_language")
}
