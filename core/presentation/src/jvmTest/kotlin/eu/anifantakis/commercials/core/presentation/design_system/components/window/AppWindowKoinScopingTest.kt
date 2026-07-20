package eu.anifantakis.commercials.core.presentation.design_system.components.window

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModel
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.core.presentation.helper.UiText
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The window scoping through **koinViewModel()** specifically - the API real
 * screens use (AppWindowHostScopingTest proves the same contract through the
 * plain `viewModel {}` factory). Koin defaults its `viewModelStoreOwner` to
 * `LocalViewModelStoreOwner.current`, which is exactly the seam
 * AppWindowHost overrides per window, so a screen hosted in a window is
 * window-scoped WITHOUT knowing anything about windows.
 *
 * This mirrors the SpotFinder wiring: content resolved with
 * `koinViewModel { parametersOf(sharedContract) }` inside `windowHost.open`.
 */
@OptIn(ExperimentalTestApi::class)
class AppWindowKoinScopingTest {

    /** Stands in for a flow-shared contract handed down via parametersOf. */
    private class SharedContract

    private class WindowScopedVm(val shared: SharedContract) : ViewModel() {
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }

    @BeforeTest
    fun setUp() {
        startKoin {
            modules(
                module {
                    single { GlobalStateContainer() }
                    factory { params -> WindowScopedVm(shared = params.get()) }
                }
            )
        }
    }

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun koinViewModelInAWindowIsScopedToThatWindow() = runComposeUiTest {
        lateinit var host: AppWindowHostState
        val shared = SharedContract()
        val seen = mutableMapOf<String, WindowScopedVm>()

        fun open(id: String) = host.open(id = id, title = UiText.Dynamic(id)) {
            // EXACTLY the SpotFinder call shape - no window awareness at all.
            val vm: WindowScopedVm = koinViewModel { parametersOf(shared) }
            SideEffect { seen[id] = vm }
            AppText("body-$id", AppTextStyle.BODY)
        }

        setContent {
            AppPreview(padded = false) {
                host = rememberAppWindowHostState()
                AppWindowHost(host)
            }
        }

        runOnIdle { open("finder-a") }
        runOnIdle { open("finder-b") }
        onNodeWithText("body-finder-a").assertExists()
        onNodeWithText("body-finder-b").assertExists()

        val a = seen.getValue("finder-a")
        val b = seen.getValue("finder-b")
        assertNotSame(a, b, "two windows => two keyed stores => two ViewModels")
        assertSame(shared, a.shared, "the flow-shared contract still arrives via parametersOf")

        // Minimize: content leaves the composition, the ViewModel must not.
        runOnIdle { host.minimize("finder-a") }
        onNodeWithText("body-finder-a").assertDoesNotExist()
        assertFalse(a.cleared, "minimizing the finder must not discard the operator's search")

        runOnIdle { host.restore("finder-a") }
        onNodeWithText("body-finder-a").assertExists()
        assertSame(a, seen.getValue("finder-a"), "restore re-attaches the SAME ViewModel")

        // Close destroys only that window's scope.
        runOnIdle { host.close("finder-a") }
        waitForIdle()
        assertTrue(a.cleared, "closing the window clears its ViewModelStore")
        assertFalse(b.cleared, "and leaves the other window's untouched")

        // Reopening the same id builds a fresh one - no resurrection.
        runOnIdle { open("finder-a") }
        onNodeWithText("body-finder-a").assertExists()
        assertNotSame(a, seen.getValue("finder-a"))
        assertEquals(false, seen.getValue("finder-a").cleared)
    }
}
