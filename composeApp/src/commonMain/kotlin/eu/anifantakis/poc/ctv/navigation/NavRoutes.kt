package eu.anifantakis.poc.ctv.navigation

import androidx.compose.runtime.*
import kotlinx.datetime.LocalDate

/**
 * Navigation routes for the POC app
 */
sealed class NavRoute {
    /**
     * Timetable screen - shows the scheduler grid with monthly view
     */
    data object Timetable : NavRoute()

    /**
     * Commercial detail screen - shows list of commercials for a specific break/date
     */
    data class CommercialDetail(
        val breakId: Long,
        val breakTime: String,
        val date: LocalDate,
        val spotCount: Int
    ) : NavRoute()
}

/**
 * Simple navigation state holder for the POC
 */
@Stable
class NavigationState(
    initialRoute: NavRoute = NavRoute.Timetable
) {
    var currentRoute by mutableStateOf<NavRoute>(initialRoute)
        private set

    private val backStack = mutableListOf<NavRoute>(initialRoute)

    fun navigateTo(route: NavRoute) {
        backStack.add(route)
        currentRoute = route
    }

    fun goBack(): Boolean {
        return if (backStack.size > 1) {
            backStack.removeLast()
            currentRoute = backStack.last()
            true
        } else {
            false
        }
    }

    fun canGoBack(): Boolean = backStack.size > 1
}

@Composable
fun rememberNavigationState(
    initialRoute: NavRoute = NavRoute.Timetable
): NavigationState {
    return remember { NavigationState(initialRoute) }
}
