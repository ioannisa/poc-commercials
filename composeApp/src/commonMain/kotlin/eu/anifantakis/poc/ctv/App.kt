package eu.anifantakis.poc.ctv

import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import eu.anifantakis.poc.ctv.data.SampleData
import eu.anifantakis.poc.ctv.grids.*
import eu.anifantakis.poc.ctv.navigation.NavRoute
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import eu.anifantakis.poc.ctv.navigation.rememberNavigationState
import eu.anifantakis.poc.ctv.screens.CommercialDetailScreen
import eu.anifantakis.poc.ctv.screens.TimetableScreen
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeContentPadding()
        ) {
            val navState = rememberNavigationState()

            // Shared state - lifted up from TimetableScreen for persistence
            var year by remember { mutableStateOf(2025) }
            var month by remember { mutableStateOf(12) }

            // Generate breaks (time slots) - stable across navigation
            val breaks = remember { SampleData.generateBreaks() }

            // Original cell data (immutable reference)
            val originalCellData = remember(year, month) {
                SampleData.generateCellData(breaks, year, month)
            }

            // Mutable cell data for modifications - persists across navigation
            val cellData = remember(year, month) {
                mutableStateMapOf<SchedulerKey, SchedulerCellData>().apply {
                    putAll(originalCellData)
                }
            }

            // Track which cells have been modified (for black background)
            val modifiedCells = remember(year, month) {
                mutableStateSetOf<SchedulerKey>()
            }

            // Selection state - use rememberSaveable to survive configuration changes (rotation)
            var selectedRow by rememberSaveable { mutableStateOf(0) }
            var selectedColumn by rememberSaveable { mutableStateOf(0) }

            AnimatedContent(
                targetState = navState.currentRoute,
                transitionSpec = {
                    if (targetState is NavRoute.CommercialDetail) {
                        // Forward navigation
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        // Back navigation
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width } + fadeOut()
                    }
                }
            ) { route ->
                when (route) {
                    is NavRoute.Timetable -> {
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
                                navState.navigateTo(
                                    NavRoute.CommercialDetail(
                                        breakId = breakId,
                                        breakTime = breakTime,
                                        date = date,
                                        spotCount = spotCount
                                    )
                                )
                            }
                        )
                    }

                    is NavRoute.CommercialDetail -> {
                        // Get the commercials for this specific cell
                        val key = SchedulerKey(route.breakId, route.date)
                        val currentCellData = cellData[key]
                        // Note: commercials is now ImmutableList in SchedulerCellData
                        val commercials = currentCellData?.commercials ?: persistentListOf()

                        CommercialDetailScreen(
                            breakId = route.breakId,
                            breakTime = route.breakTime,
                            date = StableDate(route.date), // Wrap in stable container
                            spotCount = route.spotCount,
                            commercials = commercials, // Already StableList
                            onCommercialsReorder = { reorderedList ->
                                // Update the cell data with the new order
                                val existing = cellData[key] ?: SchedulerCellData()
                                cellData[key] = existing.copy(commercials = reorderedList.toImmutableList())
                                // Mark as modified if order changed
                                if (reorderedList != originalCellData[key]?.commercials) {
                                    modifiedCells.add(key)
                                }
                            },
                            onBack = {
                                navState.goBack()
                            }
                        )
                    }
                }
            }
        }
    }
}
