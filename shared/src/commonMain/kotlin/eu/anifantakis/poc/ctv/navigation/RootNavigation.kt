package eu.anifantakis.poc.ctv.navigation

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
import androidx.savedstate.serialization.SavedStateConfiguration
import eu.anifantakis.poc.ctv.data.ScheduleRepository
import eu.anifantakis.poc.ctv.grids.BreakSlot
import eu.anifantakis.poc.ctv.grids.SchedulerCellData
import eu.anifantakis.poc.ctv.grids.SchedulerKey
import eu.anifantakis.poc.ctv.grids.StableDate
import eu.anifantakis.poc.ctv.screens.CommercialDetailScreen
import eu.anifantakis.poc.ctv.screens.TimetableScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

// Required for non-JVM platforms (iOS, JS, WASM) — registers route serializers explicitly
// since they cannot rely on reflection-based discovery.
private val navConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(CommercialNavRoute.Timetable::class, CommercialNavRoute.Timetable.serializer())
            subclass(CommercialNavRoute.CommercialDetail::class, CommercialNavRoute.CommercialDetail.serializer())
        }
    }
}

@Composable
fun RootNavigation() {
    val backStack = rememberNavBackStack(navConfig, CommercialNavRoute.Timetable)

    // Shared state lifted above NavDisplay so it persists across screen transitions
    var year by remember { mutableStateOf(2025) }
    var month by remember { mutableStateOf(12) }

    var breaks by remember { mutableStateOf<List<BreakSlot>>(emptyList()) }
    LaunchedEffect(Unit) {
        breaks = ScheduleRepository.getBreaks()
    }

    var originalCellData by remember(year, month) {
        mutableStateOf<Map<SchedulerKey, SchedulerCellData>>(emptyMap())
    }

    val cellData = remember(year, month) {
        mutableStateMapOf<SchedulerKey, SchedulerCellData>()
    }

    val modifiedCells = remember(year, month) {
        mutableStateSetOf<SchedulerKey>()
    }

    LaunchedEffect(year, month) {
        val data = ScheduleRepository.getSchedule(year, month)
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
