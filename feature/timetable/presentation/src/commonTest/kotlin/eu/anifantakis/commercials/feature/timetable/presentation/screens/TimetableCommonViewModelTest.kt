package eu.anifantakis.commercials.feature.timetable.presentation.screens

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.GlobalEffect
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
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

    private val key = SchedulerKey(TEST_TIME, TEST_DATE)

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

    /**
     * Was `loadBreaksFetchesOnceHoweverManyScreensAsk` - the station-wide cache.
     * There is no loadBreaks to call twice any more: the rows arrive WITH the
     * month, in one verb, because they are the same fact (a break is a time a spot
     * aired at). So what one load owes us is BOTH halves of the month.
     */
    @Test
    fun loadMonthLoadsTheRowsAndTheCellsTogether() = runTest(testDispatcher) {
        schedule.breaksResult = DataResult.Success(listOf(breakSlot(10), breakSlot(12)))
        schedule.monthResult = DataResult.Success(month(cell(commercials = listOf(placed(10)))))
        val vm = vm()

        vm.loadMonth(2026, 7)
        advanceUntilIdle()

        assertEquals(listOf("10:00", "12:00"), vm.commonState.value.breaks.map { it.label }, "the rows")
        assertEquals(1, vm.commonState.value.cells.size, "and the cells - one load, both halves")
        assertEquals(
            listOf(Triple(2026, 7, GridViewMode.CONDENSED)),
            schedule.breakLoads,
            "the rows are fetched FOR that month, in the current view",
        )
    }

    /**
     * Was `breaksAreStationScopedAndSurviveMonthNavigation`. That behaviour is
     * deliberately GONE: the rows were cached station-wide on the belief that an
     * airtime grid is a property of the station, and it is not - a quiet month
     * breaks at fewer times than a busy one. So the assertion inverts: navigating
     * RELOADS them.
     */
    @Test
    fun navigatingToAnotherMonthReloadsTheRows() = runTest(testDispatcher) {
        schedule.breaksResult = DataResult.Success(listOf(breakSlot(10)))
        schedule.monthResult = DataResult.Success(month(cell(commercials = listOf(placed(10)))))
        val vm = vm()
        vm.loadMonth(2026, 7)
        advanceUntilIdle()

        // August is quiet: it breaks at no time at all.
        schedule.breaksResult = DataResult.Success(emptyList())
        schedule.monthResult = DataResult.Success(month())
        vm.loadMonth(2026, 8)
        advanceUntilIdle()

        assertTrue(vm.commonState.value.breaks.isEmpty(), "the rows are the MONTH's, and August has none")
        assertTrue(vm.commonState.value.cells.isEmpty(), "and so are the cells")
        assertEquals(
            listOf(Triple(2026, 7, GridViewMode.CONDENSED), Triple(2026, 8, GridViewMode.CONDENSED)),
            schedule.breakLoads,
            "each month fetches its own rows - there is no station-wide grid to reuse",
        )
    }

    @Test
    fun clearDropsTheBreaksSoAStationSwitchRefetchesThem() = runTest(testDispatcher) {
        schedule.breaksResult = DataResult.Success(listOf(breakSlot(10)))
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()
        assertEquals(1, vm.commonState.value.breaks.size)

        vm.clear(); advanceUntilIdle()
        assertTrue(vm.commonState.value.breaks.isEmpty(), "the breaks belonged to the OLD station")

        vm.loadMonth(2026, 7); advanceUntilIdle()
        assertEquals(2, schedule.breaksFetches, "the new station's rows are fetched afresh")
    }

    /**
     * "Προβολή κάθε" reloads only the ROWS: the same airings are on screen either
     * way - the view only decides how much empty scaffold is drawn around them.
     */
    @Test
    fun setViewModeReloadsTheRowsButNotTheMonth() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(commercials = listOf(placed(10)))))
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()

        schedule.breaksResult = DataResult.Success(listOf(breakSlot(8), breakSlot(9), breakSlot(10)))
        vm.setViewMode(GridViewMode.HOURLY)
        advanceUntilIdle()

        assertEquals(GridViewMode.HOURLY, vm.commonState.value.viewMode)
        assertEquals(3, vm.commonState.value.breaks.size, "the hourly view prints its empty scaffold")
        assertEquals(
            Triple(2026, 7, GridViewMode.HOURLY),
            schedule.breakLoads.last(),
            "the SAME month's rows, in the new view",
        )
        assertEquals(1, vm.commonState.value.cells.size, "the cells are untouched - the airings did not change")
    }

    @Test
    fun setViewModeToTheModeAlreadyOnIsANoOp() = runTest(testDispatcher) {
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()
        val fetches = schedule.breaksFetches

        vm.setViewMode(GridViewMode.CONDENSED)   // already the mode
        advanceUntilIdle()

        assertEquals(fetches, schedule.breaksFetches, "re-selecting the current view must not refetch")
    }

    @Test
    fun addIncrementsCellAndMarksItModified() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(commercials = emptyList())))
        placements.nextAdded = placed(id = 100, durationSeconds = 45)
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()

        vm.add(spotId = 5, time = TEST_TIME, date = TEST_DATE)
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

        vm.removeLast(time = TEST_TIME, date = TEST_DATE)
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

        vm.add(spotId = 5, time = TEST_TIME, date = TEST_DATE); advanceUntilIdle()
        vm.removeLast(time = TEST_TIME, date = TEST_DATE); advanceUntilIdle()

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
        vm.reorder(time = TEST_TIME, date = TEST_DATE, orderedIds = listOf(10L))
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

        vm.add(spotId = 5, time = TEST_TIME, date = TEST_DATE)
        advanceUntilIdle()

        assertEquals(
            1,
            effects.count { it is GlobalEffect.SnackBarMessage },
            "the same failure must surface once, through the global snackbar - never twice, never silently",
        )
    }
}
