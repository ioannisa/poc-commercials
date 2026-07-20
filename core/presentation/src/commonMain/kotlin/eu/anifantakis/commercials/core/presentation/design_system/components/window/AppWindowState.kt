package eu.anifantakis.commercials.core.presentation.design_system.components.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import eu.anifantakis.commercials.core.presentation.helper.UiText

/**
 * How much of a window must always stay reachable inside the canvas, so a
 * dragged-out window can still be grabbed back by its title bar.
 */
internal val WINDOW_MIN_VISIBLE = 48.dp

/** New windows open centered, each next one stepped down-right by this much. */
internal val WINDOW_CASCADE_STEP = 24.dp
private const val CASCADE_STEPS = 5

/**
 * Range clamp that never throws: when the container is smaller than the
 * window's minimum, [max] can fall below [min] - the min wins.
 */
internal fun Dp.clampSafe(min: Dp, max: Dp): Dp = coerceIn(min, max.coerceAtLeast(min))

/**
 * One floating window of [AppWindowHost]: an in-canvas, movable, resizable
 * overlay that behaves like an OS window on BOTH desktop and web (same code -
 * both targets draw on a Skia canvas; no expect/actual, no platform Window).
 *
 * Geometry is in dp, canvas coordinates (top-left origin, layout-direction
 * agnostic - the host pins its positioning layer to LTR and restores the app
 * direction inside the window). [position]/[size] stay [DpOffset.Unspecified]/
 * [DpSize.Unspecified] until the first drag materializes them, so the default
 * placement can keep tracking the live container until the user takes over.
 */
@Stable
class AppWindowState internal constructor(
    val id: String,
    title: UiText,
    modal: Boolean,
    /** The caller ALLOWS undocking; whether it is docked right now is [isModal]. */
    val undockable: Boolean,
    val closable: Boolean,
    /** The caller's wish. A DOCKED window hides the action regardless - see [canMinimize]. */
    val minimizable: Boolean,
    val resizable: Boolean,
    val minSize: DpSize,
    internal val cascade: Int,
    content: @Composable () -> Unit,
) {
    var title: UiText by mutableStateOf(title)
        internal set

    /**
     * DOCKED: a scrim blocks the app beneath and this window is the only thing
     * the user can touch. Undocking (when [undockable]) drops the scrim so the
     * window becomes a tool the operator works BESIDE - the Εύρεση console's
     * two modes.
     */
    var isModal: Boolean by mutableStateOf(modal)
        internal set

    var position: DpOffset by mutableStateOf(DpOffset.Unspecified)
        internal set

    var size: DpSize by mutableStateOf(DpSize.Unspecified)
        internal set

    var isMinimized: Boolean by mutableStateOf(false)
        internal set

    /** A docked window may never hide: its scrim would block an unreachable UI. */
    val canMinimize: Boolean get() = minimizable && !isModal

    internal var zOrder: Int by mutableStateOf(0)
    internal var content: @Composable () -> Unit by mutableStateOf(content)

    /** Requires materialized geometry (the drag start writes the defaults in). */
    internal fun moveBy(delta: DpOffset, container: DpSize) {
        position = DpOffset(
            x = (position.x + delta.x)
                .clampSafe(WINDOW_MIN_VISIBLE - size.width, container.width - WINDOW_MIN_VISIBLE),
            y = (position.y + delta.y)
                .clampSafe(0.dp, container.height - WINDOW_MIN_VISIBLE),
        )
    }

    /**
     * Edge-aware resize: left/top handles move the origin AND the size so the
     * opposite edge stays put; every edge respects [minSize] and the canvas.
     */
    internal fun resizeBy(
        delta: DpOffset,
        left: Boolean,
        top: Boolean,
        right: Boolean,
        bottom: Boolean,
        container: DpSize,
    ) {
        var x = position.x
        var y = position.y
        var w = size.width
        var h = size.height
        if (right) w = (w + delta.x).clampSafe(minSize.width, container.width - x)
        if (bottom) h = (h + delta.y).clampSafe(minSize.height, container.height - y)
        if (left) {
            val dx = delta.x.coerceIn(
                -(x.coerceAtLeast(0.dp)),
                (w - minSize.width).coerceAtLeast(0.dp),
            )
            x += dx
            w -= dx
        }
        if (top) {
            val dy = delta.y.coerceIn(
                -(y.coerceAtLeast(0.dp)),
                (h - minSize.height).coerceAtLeast(0.dp),
            )
            y += dy
            h -= dy
        }
        position = DpOffset(x, y)
        size = DpSize(w, h)
    }
}

/**
 * The window manager: an observable window list plus the lifecycle-2.11.0
 * [ViewModelStoreProvider] that gives every window its OWN ViewModel scope.
 *
 * The KEYED scoping contract (the reason this exists): a window's ViewModels
 * are keyed by window [AppWindowState.id] and are NOT cleared when the window
 * merely leaves composition - minimize, z-reorder, or a transient drop
 * preserve the user's work. They are destroyed in exactly one place:
 * [close] calls [ViewModelStoreProvider.clearKey].
 */
@Stable
class AppWindowHostState internal constructor(
    internal val viewModelStoreProvider: ViewModelStoreProvider,
) {
    internal val windows = mutableStateListOf<AppWindowState>()
    internal var topZ: Int by mutableStateOf(0)
        private set
    private var openSeq = 0

    /**
     * Opens a floating window, or - if [id] is already open - refreshes its
     * title/content and brings it to the front (restoring it if minimized).
     * Same id = same ViewModel scope; a second, INDEPENDENT window of the
     * same screen just needs a different id.
     *
     * A modal (docked) window scrims and blocks everything beneath it and
     * cannot be minimized - a hidden modal would deadlock the UI behind its
     * own scrim. Pass [undockable] to let the user drop the scrim instead.
     */
    fun open(
        id: String,
        title: UiText,
        modal: Boolean = false,
        /**
         * Offers a chrome action that drops the scrim, turning a docked dialog
         * into a window the operator works BESIDE (and back). Opt-in: a window
         * whose whole point is to block - a confirmation - must not offer it.
         */
        undockable: Boolean = false,
        closable: Boolean = true,
        minimizable: Boolean = true,
        resizable: Boolean = true,
        minSize: DpSize = DpSize(280.dp, 180.dp),
        content: @Composable () -> Unit,
    ) {
        val existing = windows.firstOrNull { it.id == id }
        if (existing != null) {
            existing.title = title
            existing.content = content
            existing.isMinimized = false
            focus(id)
            return
        }
        windows += AppWindowState(
            id = id,
            title = title,
            modal = modal,
            undockable = undockable,
            closable = closable,
            minimizable = minimizable,
            resizable = resizable,
            minSize = minSize,
            cascade = openSeq++ % CASCADE_STEPS,
            content = content,
        ).also { it.zOrder = ++topZ }
    }

    /**
     * Docks (scrim, blocks everything) or undocks the window. Undocking also
     * brings it to the front: it just stopped being the only reachable thing,
     * so its z-order starts mattering.
     */
    fun setModal(id: String, modal: Boolean) {
        val window = windows.firstOrNull { it.id == id }?.takeIf { it.undockable } ?: return
        if (window.isModal == modal) return
        window.isModal = modal
        // A docked window can never be hidden - dock a minimized one and it
        // must come back, or its scrim would block a UI with nothing on top.
        if (modal) window.isMinimized = false
        focus(id)
    }

    fun focus(id: String) {
        val window = windows.firstOrNull { it.id == id } ?: return
        if (window.zOrder != topZ) window.zOrder = ++topZ
    }

    /** Parks the window into the taskbar strip - its ViewModels SURVIVE. */
    fun minimize(id: String) {
        windows.firstOrNull { it.id == id }?.takeIf { it.canMinimize }?.isMinimized = true
    }

    fun restore(id: String) {
        windows.firstOrNull { it.id == id }?.isMinimized = false
        focus(id)
    }

    /** Closes the window AND destroys its ViewModels - the ONLY clearKey site. */
    fun close(id: String) {
        if (windows.removeAll { it.id == id }) viewModelStoreProvider.clearKey(id)
    }

    fun closeAll() {
        windows.toList().forEach { close(it.id) }
    }

    fun isOpen(id: String): Boolean = windows.any { it.id == id }
}

/**
 * Creates the host state at the app-chrome level. The provider parents itself
 * to the surrounding [androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner]
 * (the platform root owner in NavigationRoot), so window ViewModels die with
 * the app - never sooner than their window, never later than the app.
 */
@Composable
fun rememberAppWindowHostState(): AppWindowHostState {
    val provider = rememberViewModelStoreProvider()
    return remember(provider) { AppWindowHostState(provider) }
}

/**
 * Ambient access for screens: any composable under the host can open a
 * floating window without threading the state through parameters.
 */
val LocalAppWindowHost = staticCompositionLocalOf<AppWindowHostState> {
    error("No AppWindowHost found - provide LocalAppWindowHost at the app chrome (see NavigationRoot).")
}
