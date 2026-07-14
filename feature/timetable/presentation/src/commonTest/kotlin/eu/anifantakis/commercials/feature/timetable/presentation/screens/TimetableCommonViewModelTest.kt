package eu.anifantakis.commercials.feature.timetable.presentation.screens

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.GlobalEffect
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reducer of the flow-shared owner: every shared mutation, verified
 * through the [TimetableCommon] state it produces. Fakes stand in for both
 * domain repositories (mandatory in KMP - testing.md).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimetableCommonViewModelTest : TimetableTestBase() {

    private val schedule = FakeScheduleRepository()
    private val placements = FakePlacementsRepository()

    private fun vm() = TimetableCommonViewModel(schedule, placements)

    private val key = SchedulerKey(1L, TEST_DATE)

    @Test
    fun loadMonthPopulatesCells() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(commercials = listOf(placed(10), placed(11)))))
        val vm = vm()

        vm.loadMonth(2026, 7)
        advanceUntilIdle()

        val cells = vm.commonState.value.cells
        assertEquals(1, cells.size)
        assertEquals(2, cells[key]?.spotCount)
    }

    @Test
    fun loadBreaksFetchesOnceHoweverManyScreensAsk() = runTest(testDispatcher) {
        schedule.breaksResult = DataResult.Success(listOf(breakSlot(1L, 10), breakSlot(2L, 12)))
        val vm = vm()

        vm.loadBreaks()   // the grid asks
        vm.loadBreaks()   // the Break Console asks
        advanceUntilIdle()

        assertEquals(1, schedule.breaksFetches, "the station's grid is fetched exactly once")
        assertEquals(listOf(1L, 2L), vm.commonState.value.breaks.map { it.id })
    }

    @Test
    fun breaksAreStationScopedAndSurviveMonthNavigation() = runTest(testDispatcher) {
        schedule.breaksResult = DataResult.Success(listOf(breakSlot(1L, 10)))
        schedule.monthResult = DataResult.Success(month(cell(commercials = listOf(placed(10)))))
        val vm = vm()
        vm.loadBreaks()
        vm.loadMonth(2026, 7)
        advanceUntilIdle()

        schedule.monthResult = DataResult.Success(month())
        vm.loadMonth(2026, 8)
        advanceUntilIdle()

        assertEquals(1, vm.commonState.value.breaks.size, "the rows belong to the station, not the month")
        assertTrue(vm.commonState.value.cells.isEmpty(), "but the month's cells were replaced")
        assertEquals(1, schedule.breaksFetches, "and no second /api/breaks was issued")
    }

    @Test
    fun clearDropsTheBreaksSoAStationSwitchRefetchesThem() = runTest(testDispatcher) {
        schedule.breaksResult = DataResult.Success(listOf(breakSlot(1L, 10)))
        val vm = vm()
        vm.loadBreaks(); advanceUntilIdle()

        vm.clear(); advanceUntilIdle()
        assertTrue(vm.commonState.value.breaks.isEmpty(), "the breaks belonged to the OLD station")

        vm.loadBreaks(); advanceUntilIdle()
        assertEquals(2, schedule.breaksFetches, "the new station's grid is fetched afresh")
    }

    @Test
    fun addIncrementsCellAndMarksItModified() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(commercials = emptyList())))
        placements.nextAdded = placed(id = 100, durationSeconds = 45)
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()

        vm.add(spotId = 5, breakId = 1, date = TEST_DATE)
        advanceUntilIdle()

        val state = vm.commonState.value
        val cell = state.cells[key]!!
        assertEquals(1, cell.spotCount)
        assertEquals(45, cell.totalDurationSeconds)
        assertTrue(key in state.modifiedCells, "the added cell must carry the black marker")
        assertEquals(1, state.addedCounts[key], "one session add -> 'r' becomes enabled once")
    }

    @Test
    fun removeLastIsNoOpWhenNothingWasAddedThisSession() = runTest(testDispatcher) {
        // A cell full of server-loaded placements (not session-added) must not be touchable by 'r'.
        schedule.monthResult = DataResult.Success(month(cell(commercials = listOf(placed(10)))))
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()

        vm.removeLast(breakId = 1, date = TEST_DATE)
        advanceUntilIdle()

        assertTrue(placements.removedIds.isEmpty(), "'r' must never delete a placement we did not add")
        assertEquals(1, vm.commonState.value.cells[key]?.spotCount, "the cell is unchanged")
    }

    @Test
    fun addThenRemoveLastRestoresTheCellAndClearsMarkers() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(commercials = emptyList())))
        placements.nextAdded = placed(id = 100, durationSeconds = 30)
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()

        vm.add(spotId = 5, breakId = 1, date = TEST_DATE); advanceUntilIdle()
        vm.removeLast(breakId = 1, date = TEST_DATE); advanceUntilIdle()

        val state = vm.commonState.value
        assertEquals(0, state.cells[key]?.spotCount)
        assertEquals(listOf(100L), placements.removedIds, "remove is called with the placement we added")
        assertFalse(key in state.modifiedCells, "the marker clears once the session stack empties")
        assertEquals(0, state.addedCounts[key])
    }

    @Test
    fun reorderWithStaleIdsLeavesTheCellUnchanged() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(commercials = listOf(placed(10), placed(11)))))
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()

        // Only one id for a two-placement cell: the client's view is stale.
        vm.reorder(breakId = 1, date = TEST_DATE, orderedIds = listOf(10L))
        advanceUntilIdle()

        assertEquals(
            listOf(10L, 11L),
            vm.commonState.value.cells[key]?.commercials?.map { it.id },
            "a size-mismatched reorder must not scramble the cell",
        )
    }

    @Test
    fun aRepositoryFailureSurfacesExactlyOneSnackbar() = runTest(testDispatcher) {
        placements.addResult = DataResult.Failure(DataError.Network.NO_INTERNET)
        val vm = vm()

        val effects = mutableListOf<GlobalEffect>()
        backgroundScope.launch { globalContainer.effects.collect { effects += it } }

        vm.add(spotId = 5, breakId = 1, date = TEST_DATE)
        advanceUntilIdle()

        assertEquals(
            1,
            effects.count { it is GlobalEffect.SnackBarMessage },
            "the same failure must surface once, through the global snackbar - never twice, never silently",
        )
    }
}
