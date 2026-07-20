package eu.anifantakis.commercials.feature.preferences.presentation.screens.preferences

import eu.anifantakis.commercials.core.domain.preferences.AppLanguageStore
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.core.presentation.string_resources.Language
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationManager
import eu.anifantakis.commercials.feature.preferences.domain.FontSizePreference
import eu.anifantakis.commercials.feature.preferences.domain.ThemePreference
import eu.anifantakis.commercials.feature.preferences.domain.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The gear screen's ViewModel. Proves the two edges it owns: the theme
 * delegates to the [UserPreferences] snapshot seam, and a language choice is
 * BOTH persisted (the one [AppLanguageStore] KSafe entry) AND applied to the
 * global in-memory [LocalizationManager] — persistence at the edge, switch in
 * memory.
 */
class PreferencesViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        startKoin { modules(module { single { GlobalStateContainer() } }) }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
        // LocalizationManager is a global object - reset so tests don't leak.
        LocalizationManager.setLanguage(Language.FALLBACK)
    }

    private class FakeUserPreferences(
        override var theme: ThemePreference = ThemePreference.SYSTEM,
        override var fontSize: FontSizePreference = FontSizePreference.MEDIUM,
        // Panel geometry: the ViewModel never touches it, the host does.
        override var panelWidthDp: Int = UserPreferences.DEFAULT_PANEL_WIDTH_DP,
    ) : UserPreferences

    private class FakeAppLanguageStore(override var languageCode: String = "") : AppLanguageStore

    private fun vm(
        prefs: FakeUserPreferences = FakeUserPreferences(),
        store: FakeAppLanguageStore = FakeAppLanguageStore(),
    ) = PreferencesViewModel(prefs, store)

    @Test
    fun themeReflectsTheUnderlyingPreference() = runTest(testDispatcher) {
        val prefs = FakeUserPreferences(theme = ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, vm(prefs).theme, "the getter reads straight through the prefs seam")
    }

    @Test
    fun themeSelectedPersistsToPreferences() = runTest(testDispatcher) {
        val prefs = FakeUserPreferences(theme = ThemePreference.SYSTEM)
        val vm = vm(prefs)

        vm.onAction(PreferencesIntent.ThemeSelected(ThemePreference.LIGHT))

        assertEquals(ThemePreference.LIGHT, prefs.theme, "the choice is written to the snapshot-backed prefs")
    }

    @Test
    fun fontSizeSelectedPersistsToPreferences() = runTest(testDispatcher) {
        val prefs = FakeUserPreferences()
        val vm = vm(prefs)

        vm.onAction(PreferencesIntent.FontSizeSelected(FontSizePreference.XLARGE))

        assertEquals(FontSizePreference.XLARGE, prefs.fontSize, "the slider step is written to the prefs seam")
    }

    @Test
    fun languageSelectedPersistsTheCodeAndSwitchesTheManager() = runTest(testDispatcher) {
        val store = FakeAppLanguageStore(languageCode = "en")
        val vm = vm(store = store)

        vm.onAction(PreferencesIntent.LanguageSelected(Language.EL))

        assertEquals("el", store.languageCode, "the choice survives restarts via the one KSafe entry")
        assertEquals(Language.EL, LocalizationManager.current, "and takes effect live for every reader")
    }
}
