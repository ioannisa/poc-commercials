package eu.anifantakis.commercials.core.presentation.navigation

import androidx.navigation3.runtime.NavKey

/**
 * All navigation goes through this small wrapper so intent is named and
 * screens never touch the raw list. The app layer owns the backing list
 * (a persisted rememberNavBackStack - the stack survives process death);
 * feature entry providers receive the Navigator, ScreenRoots never do.
 */
class Navigator(val backStack: MutableList<NavKey>) {

    /** Push a child route onto the current back stack. */
    fun navigate(route: NavKey) {
        backStack.add(route)
    }

    /** Pop the top of the back stack. Wired to NavDisplay.onBack. */
    fun goBack() {
        backStack.removeLastOrNull()
    }

    /** Clear the entire back stack and start fresh at [route] - flow switch / logout. */
    fun resetTo(route: NavKey) {
        backStack.clear()
        backStack.add(route)
    }

    fun current(): NavKey? = backStack.lastOrNull()
}
