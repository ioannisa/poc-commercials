package eu.anifantakis.commercials.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import eu.anifantakis.commercials.core.presentation.design_system.components.window.AppWindowHostState
import eu.anifantakis.commercials.core.presentation.helper.UiText

/**
 * Opens app SCREENS (NavKey routes) as floating windows: the route renders
 * through the SAME entry catalog as the fullscreen NavDisplay, so a screen
 * never knows whether it runs fullscreen or in a window - same entry, same
 * callbacks, and (via the window host's keyed scoping) its OWN ViewModel.
 *
 * The design-system [AppWindowHostState] stays navigation-blind (slot-based,
 * in :core:presentation); this app-layer wrapper is the only place windows
 * and Navigation3 meet. Ad-hoc content needs no route - call
 * [AppWindowHostState.open] on [windows] directly.
 */
@Stable
class WindowNavigator internal constructor(
    val windows: AppWindowHostState,
) {
    // Refreshed by rememberWindowNavigator every composition and read at
    // RENDER time, so window content never captures a stale entry catalog.
    internal var entries: ((NavKey) -> NavEntry<NavKey>)? = null

    /**
     * Same [id] focuses the existing window (same ViewModel scope); pass a
     * distinct id to open a second, fully independent window of the same
     * screen - two ids, two ViewModel scopes.
     */
    fun openScreen(
        route: NavKey,
        title: UiText,
        id: String = route.toString(),
        modal: Boolean = false,
        closable: Boolean = true,
        minimizable: Boolean = !modal,
        resizable: Boolean = true,
        minSize: DpSize = DpSize(280.dp, 180.dp),
    ) {
        windows.open(
            id = id,
            title = title,
            modal = modal,
            closable = closable,
            minimizable = minimizable,
            resizable = resizable,
            minSize = minSize,
        ) {
            val provider = checkNotNull(entries) {
                "WindowNavigator used before NavigationRoot installed its entry catalog"
            }
            provider(route).Content()
        }
    }
}

@Composable
internal fun rememberWindowNavigator(
    windows: AppWindowHostState,
    entries: (NavKey) -> NavEntry<NavKey>,
): WindowNavigator {
    val navigator = remember(windows) { WindowNavigator(windows) }
    navigator.entries = entries
    return navigator
}

val LocalWindowNavigator = staticCompositionLocalOf<WindowNavigator> {
    error("No WindowNavigator - NavigationRoot provides it above the NavDisplay.")
}
