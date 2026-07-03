package eu.anifantakis.commercials.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.savedstate.serialization.SavedStateConfiguration
import eu.anifantakis.commercials.auth.AuthApi
import eu.anifantakis.commercials.auth.AuthSession
import eu.anifantakis.commercials.data.ScheduleRepository
import eu.anifantakis.commercials.grids.BreakSlot
import eu.anifantakis.commercials.grids.SchedulerCellData
import eu.anifantakis.commercials.grids.SchedulerKey
import eu.anifantakis.commercials.grids.StableDate
import eu.anifantakis.commercials.screens.CommercialDetailScreen
import eu.anifantakis.commercials.screens.LoginScreen
import eu.anifantakis.commercials.screens.TimetableScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

// Required for non-JVM platforms (iOS, JS, WASM) — registers route serializers explicitly
// since they cannot rely on reflection-based discovery.
private val navConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(CommercialNavRoute.Login::class, CommercialNavRoute.Login.serializer())
            subclass(CommercialNavRoute.Timetable::class, CommercialNavRoute.Timetable.serializer())
            subclass(CommercialNavRoute.CommercialDetail::class, CommercialNavRoute.CommercialDetail.serializer())
        }
    }
}

@Composable
fun RootNavigation() {
    val scope = rememberCoroutineScope()
    val authSession = koinInject<AuthSession>()
    val authApi = koinInject<AuthApi>()
    val scheduleRepository = koinInject<ScheduleRepository>()

    // Token persists (no expiry), so a returning user skips the login screen
    val backStack = rememberNavBackStack(
        navConfig,
        if (authSession.isLoggedIn) CommercialNavRoute.Timetable else CommercialNavRoute.Login
    )

    // Shared state lifted above NavDisplay so it persists across screen transitions
    var year by remember { mutableStateOf(2025) }
    var month by remember { mutableStateOf(12) }

    // Data loading is keyed on the session revision: nothing is fetched until
    // login succeeds, and switching users (logout -> login) refetches with the
    // new token - which matters because the server filters data per role.
    val authRevision = authSession.revision

    var breaks by remember { mutableStateOf<List<BreakSlot>>(emptyList()) }
    LaunchedEffect(authRevision) {
        if (authSession.isLoggedIn) breaks = scheduleRepository.getBreaks()
    }

    // Also keyed on the session revision: a station switch (or user switch)
    // must start from a clean grid - cells are keyed by breakId+date, which
    // collide across stations, so stale edits/modified flags would otherwise
    // leak from the previously selected station.
    var originalCellData by remember(year, month, authRevision) {
        mutableStateOf<Map<SchedulerKey, SchedulerCellData>>(emptyMap())
    }

    val cellData = remember(year, month, authRevision) {
        mutableStateMapOf<SchedulerKey, SchedulerCellData>()
    }

    val modifiedCells = remember(year, month, authRevision) {
        mutableStateSetOf<SchedulerKey>()
    }

    LaunchedEffect(year, month, authRevision) {
        if (!authSession.isLoggedIn) return@LaunchedEffect
        val data = scheduleRepository.getSchedule(year, month)
        originalCellData = data
        cellData.clear()
        cellData.putAll(data)
    }

    var selectedRow by rememberSaveable { mutableStateOf(0) }
    var selectedColumn by rememberSaveable { mutableStateOf(0) }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),   // must be first
            rememberViewModelStoreNavEntryDecorator()
        ),
        transitionSpec = {
            slideInHorizontally { it } + fadeIn() togetherWith
                    slideOutHorizontally { -it } + fadeOut()
        },
        popTransitionSpec = {
            slideInHorizontally { -it } + fadeIn() togetherWith
                    slideOutHorizontally { it } + fadeOut()
        },
        entryProvider = entryProvider {

            entry<CommercialNavRoute.Login> {
                LoginScreen(
                    onLoggedIn = {
                        backStack.clear()
                        backStack.add(CommercialNavRoute.Timetable)
                    }
                )
            }

            entry<CommercialNavRoute.Timetable> {
                TimetableScreen(
                    year = year,
                    month = month,
                    breaks = breaks.toImmutableList(),
                    cellData = cellData,
                    originalCellData = originalCellData.toImmutableMap(),
                    modifiedCells = modifiedCells,
                    selectedRow = selectedRow,
                    selectedColumn = selectedColumn,
                    onYearChange = { year = it },
                    onMonthChange = { month = it },
                    onSelectionChange = { row, col ->
                        selectedRow = row
                        selectedColumn = col
                    },
                    onCellClick = { breakId, breakTime, date, spotCount ->
                        backStack.add(
                            CommercialNavRoute.CommercialDetail(
                                breakId = breakId,
                                breakTime = breakTime,
                                date = date,
                                spotCount = spotCount
                            )
                        )
                    },
                    onLogout = {
                        scope.launch {
                            authApi.logout()   // revokes the token server-side, clears the session
                            breaks = emptyList()
                            originalCellData = emptyMap()
                            cellData.clear()
                            modifiedCells.clear()
                            backStack.clear()
                            backStack.add(CommercialNavRoute.Login)
                        }
                    }
                )
            }

            entry<CommercialNavRoute.CommercialDetail> { route ->
                val key = SchedulerKey(route.breakId, route.date)
                val commercials = cellData[key]?.commercials ?: persistentListOf()

                CommercialDetailScreen(
                    breakId = route.breakId,
                    breakTime = route.breakTime,
                    date = StableDate(route.date),
                    spotCount = route.spotCount,
                    commercials = commercials,
                    onCommercialsReorder = { reorderedList ->
                        val existing = cellData[key] ?: SchedulerCellData()
                        cellData[key] = existing.copy(commercials = reorderedList.toImmutableList())
                        if (reorderedList != originalCellData[key]?.commercials) {
                            modifiedCells.add(key)
                        }
                    },
                    onBack = { backStack.removeLastOrNull() }
                )
            }

        }
    )
}
