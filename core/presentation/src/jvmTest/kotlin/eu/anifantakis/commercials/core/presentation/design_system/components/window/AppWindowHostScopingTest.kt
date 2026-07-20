package eu.anifantakis.commercials.core.presentation.design_system.components.window

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import eu.anifantakis.commercials.core.presentation.helper.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The floating-window ViewModel contract, exercised in a REAL composition
 * (lifecycle 2.11.0 keyed ViewModelStoreProvider under the hood):
 *
 *  1. a window's ViewModel is created when the window opens,
 *  2. SURVIVES minimize/restore even though the content fully leaves the
 *     composition (the whole point of the KEYED owner),
 *  3. is destroyed by close() - and ONLY by close(),
 *  4. two windows of the same content get two independent ViewModels.
 */
@OptIn(ExperimentalTestApi::class)
class AppWindowHostScopingTest {

    private class WindowVm : ViewModel() {
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }

    @Test
    fun viewModel_survivesMinimize_diesOnlyOnClose() = runComposeUiTest {
        lateinit var host: AppWindowHostState
        val instances = LinkedHashSet<WindowVm>()

        fun openWindow() = host.open(id = "w1", title = UiText.Dynamic("Scoping")) {
            val vm = viewModel { WindowVm() }
            SideEffect { instances += vm }
            AppText("window-body", AppTextStyle.BODY)
        }

        setContent {
            AppPreview(padded = false) {
                host = rememberAppWindowHostState()
                AppWindowHost(host)
            }
        }

        runOnIdle(::openWindow)
        onNodeWithText("window-body").assertExists()
        assertEquals(1, instances.size)
        val vm1 = instances.single()

        // Minimize: the content demonstrably LEAVES the composition...
        runOnIdle { host.minimize("w1") }
        onNodeWithText("window-body").assertDoesNotExist()
        assertFalse(vm1.cleared, "minimize must NOT clear the ViewModel")

        // ...and restore re-attaches the SAME instance.
        runOnIdle { host.restore("w1") }
        onNodeWithText("window-body").assertExists()
        assertEquals(1, instances.size, "restore must re-use the keyed store, not build a new ViewModel")
        assertSame(vm1, instances.single())
        assertFalse(vm1.cleared)

        // Close destroys the scope...
        runOnIdle { host.close("w1") }
        onNodeWithText("window-body").assertDoesNotExist()
        waitForIdle()
        assertTrue(vm1.cleared, "close() must clearKey the window's ViewModelStore")

        // ...and reopening the same id starts a FRESH one.
        runOnIdle(::openWindow)
        onNodeWithText("window-body").assertExists()
        assertEquals(2, instances.size, "a reopened id must not resurrect the closed ViewModel")
        assertNotSame(vm1, instances.last())
        assertFalse(instances.last().cleared)
    }

    @Test
    fun twoWindows_ofTheSameContent_getIndependentViewModels() = runComposeUiTest {
        lateinit var host: AppWindowHostState
        val byWindow = mutableMapOf<String, WindowVm>()

        fun openWindow(id: String) = host.open(id = id, title = UiText.Dynamic(id)) {
            val vm = viewModel { WindowVm() }
            SideEffect { byWindow[id] = vm }
            AppText("body-$id", AppTextStyle.BODY)
        }

        setContent {
            AppPreview(padded = false) {
                host = rememberAppWindowHostState()
                AppWindowHost(host)
            }
        }

        runOnIdle { openWindow("a") }
        runOnIdle { openWindow("b") }
        onNodeWithText("body-a").assertExists()
        onNodeWithText("body-b").assertExists()

        val vmA = byWindow.getValue("a")
        val vmB = byWindow.getValue("b")
        assertNotSame(vmA, vmB, "each window id must own its own ViewModel scope")

        // Closing one window must not touch the other's scope.
        runOnIdle { host.close("a") }
        waitForIdle()
        assertTrue(vmA.cleared)
        assertFalse(vmB.cleared)
        onNodeWithText("body-b").assertExists()
    }
}
