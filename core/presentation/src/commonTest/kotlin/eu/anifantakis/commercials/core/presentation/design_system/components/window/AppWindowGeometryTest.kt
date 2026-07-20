package eu.anifantakis.commercials.core.presentation.design_system.components.window

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.helper.UiText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The window-manager clamp math is pure state arithmetic - tested here
 * without composition. The scoping CONTRACT (keyed ViewModel lifetimes)
 * lives in jvmTest/AppWindowHostScopingTest, which needs a real composition.
 */
class AppWindowGeometryTest {

    private val container = DpSize(800.dp, 600.dp)

    private fun window(
        position: DpOffset = DpOffset(100.dp, 100.dp),
        size: DpSize = DpSize(400.dp, 300.dp),
        minSize: DpSize = DpSize(280.dp, 180.dp),
    ) = AppWindowState(
        id = "test",
        title = UiText.Dynamic("Test"),
        modal = false,
        undockable = false,
        closable = true,
        minimizable = true,
        resizable = true,
        minSize = minSize,
        cascade = 0,
        content = {},
    ).apply {
        this.position = position
        this.size = size
    }

    @Test
    fun move_clampsSoTheTitleBarStaysReachable() {
        val w = window()
        w.moveBy(DpOffset(10_000.dp, 10_000.dp), container)
        // At least WINDOW_MIN_VISIBLE must remain inside the canvas.
        assertEquals(container.width - WINDOW_MIN_VISIBLE, w.position.x)
        assertEquals(container.height - WINDOW_MIN_VISIBLE, w.position.y)

        w.moveBy(DpOffset((-10_000).dp, (-10_000).dp), container)
        assertEquals(WINDOW_MIN_VISIBLE - w.size.width, w.position.x)
        assertEquals(0.dp, w.position.y, "the title bar may never leave through the top")
    }

    @Test
    fun resizeRight_growsToTheCanvasAndNeverBelowMin() {
        val w = window()
        w.resizeBy(DpOffset(10_000.dp, 0.dp), left = false, top = false, right = true, bottom = false, container = container)
        assertEquals(container.width - 100.dp, w.size.width, "right edge stops at the canvas")

        w.resizeBy(DpOffset((-10_000).dp, 0.dp), left = false, top = false, right = true, bottom = false, container = container)
        assertEquals(280.dp, w.size.width, "shrink floors at minSize")
        assertEquals(DpOffset(100.dp, 100.dp), w.position, "east resize never moves the origin")
    }

    @Test
    fun resizeLeft_movesOriginAndSizeTogether() {
        val w = window()
        w.resizeBy(DpOffset((-50).dp, 0.dp), left = true, top = false, right = false, bottom = false, container = container)
        assertEquals(50.dp, w.position.x)
        assertEquals(450.dp, w.size.width)

        // Fling far left: the edge stops at the canvas border.
        w.resizeBy(DpOffset((-10_000).dp, 0.dp), left = true, top = false, right = false, bottom = false, container = container)
        assertEquals(0.dp, w.position.x)
        assertEquals(500.dp, w.size.width)

        // Fling far right: shrink floors at minSize, origin follows.
        w.resizeBy(DpOffset(10_000.dp, 0.dp), left = true, top = false, right = false, bottom = false, container = container)
        assertEquals(280.dp, w.size.width)
        assertEquals(220.dp, w.position.x, "opposite (east) edge stayed at 500dp")
    }

    @Test
    fun resizeTopCorner_bothAxesClampIndependently()  {
        val w = window()
        w.resizeBy(DpOffset((-10_000).dp, (-10_000).dp), left = true, top = true, right = false, bottom = false, container = container)
        assertEquals(DpOffset(0.dp, 0.dp), w.position)
        assertEquals(DpSize(500.dp, 400.dp), w.size)
    }

    @Test
    fun hostState_reopenSameId_focusesInsteadOfDuplicating() {
        val host = AppWindowHostState(FakeStoreProvider.provider)
        host.open("a", UiText.Dynamic("A")) {}
        host.open("b", UiText.Dynamic("B")) {}
        assertEquals(2, host.windows.size)
        val zOfA = host.windows.first { it.id == "a" }.zOrder

        host.open("a", UiText.Dynamic("A2")) {}
        assertEquals(2, host.windows.size, "same id must not duplicate")
        val a = host.windows.first { it.id == "a" }
        assertEquals("A2", (a.title as UiText.Dynamic).value)
        assertEquals(true, a.zOrder > zOfA, "reopen brings to front")
    }

    @Test
    fun modalWindows_cannotBeMinimized() {
        val host = AppWindowHostState(FakeStoreProvider.provider)
        host.open("m", UiText.Dynamic("Modal"), modal = true) {}
        host.minimize("m")
        assertEquals(false, host.windows.single().isMinimized, "a hidden modal would deadlock the UI")
    }

    // ── dock / undock (the Εύρεση console's two modes) ──────────────────

    @Test
    fun undockingDropsTheScrimAndUnlocksMinimize() {
        val host = AppWindowHostState(FakeStoreProvider.provider)
        host.open("f", UiText.Dynamic("Finder"), modal = true, undockable = true) {}
        val w = host.windows.single()
        assertEquals(true, w.isModal)
        assertEquals(false, w.canMinimize, "docked: the minimize action is not even offered")

        host.setModal("f", false)

        assertEquals(false, w.isModal, "undocked - the app beneath takes clicks again")
        assertEquals(true, w.canMinimize, "and it can now be parked while the operator works")
    }

    @Test
    fun dockingAMinimizedWindowBringsItBack() {
        val host = AppWindowHostState(FakeStoreProvider.provider)
        host.open("f", UiText.Dynamic("Finder"), modal = false, undockable = true) {}
        host.minimize("f")
        assertEquals(true, host.windows.single().isMinimized)

        host.setModal("f", true)

        assertEquals(false, host.windows.single().isMinimized, "a hidden docked window would scrim an unreachable UI")
    }

    @Test
    fun aWindowThatIsNotUndockableIgnoresTheModeSwitch() {
        val host = AppWindowHostState(FakeStoreProvider.provider)
        host.open("c", UiText.Dynamic("Confirm"), modal = true) {}   // undockable defaults to false

        host.setModal("c", false)

        assertEquals(true, host.windows.single().isModal, "a window whose point is to block must stay blocking")
    }
}

/**
 * close() calls clearKey on the real ViewModelStoreProvider; for the pure
 * state tests a root provider with no parent is enough.
 */
private object FakeStoreProvider {
    val provider = androidx.lifecycle.viewmodel.ViewModelStoreProvider(parentStore = null)
}
