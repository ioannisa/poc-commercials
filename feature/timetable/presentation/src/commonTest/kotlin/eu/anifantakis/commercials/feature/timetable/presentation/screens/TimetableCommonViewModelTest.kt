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
        schedule.monthResult = DataResult.Success(month(cell(spots = listOf(placed(10), placed(11)))))
        val vm = vm()

        vm.loadMonth(2026, 7)
        advanceUntilIdle()

        val cells = vm.commonState.value.cells
        assertEquals(1, cells.size)
        assertEquals(2, cells[key]?.spotCount)
    }

    /**
     * The month is an AGGREGATE: a count, a duration and a colour per cell. The
     * airings are NOT in it - shipping the month's 13,009 of them to draw 1,295
     * boxes cost 7.79 MB, and the grid never reads one. Whoever needs them (the
     * Break Console, a report) fetches its own slice.
     */
    @Test
    fun loadMonthCarriesTheCountsButNotTheAirings() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(spots = listOf(placed(10), placed(11)))))
        schedule.stock(TEST_TIME, TEST_DATE, placed(10), placed(11))
        val vm = vm()

        vm.loadMonth(2026, 7)
        advanceUntilIdle()

        assertEquals(2, vm.commonState.value.cells[key]?.spotCount, "the cell knows HOW MANY spots air in it")
        assertTrue(
            vm.commonState.value.cells[key]?.commercials?.isEmpty() == true,
            "but not WHICH - the month grid does not carry airings, however many the server holds",
        )
        assertEquals(0, schedule.commercialsFetches, "and loading a month must not go and fetch them either")
    }

    /** The Break Console's fetch: ONE cell's airings, merged into that cell. */
    @Test
    fun loadCommercialsMergesOneCellsAiringsIntoTheStore() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(spots = listOf(placed(10), placed(11)))))
        schedule.stock(TEST_TIME, TEST_DATE, placed(10), placed(11))
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()

        vm.loadCommercials(TEST_TIME, TEST_DATE)
        advanceUntilIdle()

        assertEquals(
            listOf(10L, 11L),
            vm.commonState.value.cells[key]?.commercials?.map { it.id },
            "the cell's airings arrive on demand and merge into the cell it already had",
        )
        assertEquals(2, vm.commonState.value.cells[key]?.spotCount, "the aggregate the grid draws is untouched")
        assertEquals(
            listOf(CommercialsQuery(2026, 7, TEST_DATE, TEST_TIME)),
            schedule.commercialLoads,
            "and it asks for THAT ONE CELL - not the day, not the month",
        )
    }

    /** Idempotent: a cell that already holds its airings is never fetched twice. */
    @Test
    fun loadCommercialsDoesNotRefetchACellThatAlreadyHasThem() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(spots = listOf(placed(10)))))
        schedule.stock(TEST_TIME, TEST_DATE, placed(10))
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()

        vm.loadCommercials(TEST_TIME, TEST_DATE); advanceUntilIdle()
        vm.loadCommercials(TEST_TIME, TEST_DATE); advanceUntilIdle()

        assertEquals(
            1,
            schedule.commercialsFetches,
            "re-opening the same break (or paging back to it) must not go to the network again",
        )
        assertEquals(listOf(10L), vm.commonState.value.cells[key]?.commercials?.map { it.id })
    }

    /**
     * The other way a cell comes to hold airings: 'a' just added one. Opening the
     * console on it must not fetch a list the store already has - and would
     * overwrite the optimistic add with.
     */
    @Test
    fun loadCommercialsSkipsACellASessionAddJustFilled() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(spots = emptyList())))
        placements.nextAdded = placed(id = 100)
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()
        vm.add(spotId = 5, time = TEST_TIME, date = TEST_DATE); advanceUntilIdle()

        vm.loadCommercials(TEST_TIME, TEST_DATE)
        advanceUntilIdle()

        assertEquals(0, schedule.commercialsFetches, "the cell already holds what we just put in it")
        assertEquals(listOf(100L), vm.commonState.value.cells[key]?.commercials?.map { it.id })
    }

    /**
     * Was `loadBreaksFetchesOnceHoweverManyScreensAsk` - the station-wide cache.
     * There is no loadBreaks to call twice any more: the rows arrive WITH the
     * month, in one verb, because they are the same fact (a break is a time a spot
     * aired at). So what one load owes us is BOTH halves of the month.
     */
    @Test
    fun loadMonthLoadsTheRowsAndTheCellsTogether() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(
            month(cell(spots = listOf(placed(10))), rows = listOf(breakSlot(10), breakSlot(12)))
        )
        val vm = vm()

        vm.loadMonth(2026, 7)
        advanceUntilIdle()

        assertEquals(listOf("10:00", "12:00"), vm.commonState.value.breaks.map { it.label }, "the rows")
        assertEquals(1, vm.commonState.value.cells.size, "and the cells - one load, both halves")
        // ONE round trip, not two. The rows ARE the distinct times of the cells, so
        // the server derives them from the same scan; fetching them separately made
        // the screen wait twice and scanned the month twice.
        assertEquals(
            listOf(GridViewMode.CONDENSED),
            schedule.monthLoads,
            "the grid is one call, in the current view",
        )
        assertTrue(schedule.breakLoads.isEmpty(), "and it does NOT also hit the rows endpoint")
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
        schedule.monthResult = DataResult.Success(
            month(cell(spots = listOf(placed(10))), rows = listOf(breakSlot(10)))
        )
        val vm = vm()
        vm.loadMonth(2026, 7)
        advanceUntilIdle()

        // August is quiet: it breaks at no time at all.
        schedule.monthResult = DataResult.Success(month())
        vm.loadMonth(2026, 8)
        advanceUntilIdle()

        assertTrue(vm.commonState.value.breaks.isEmpty(), "the rows are the MONTH's, and August has none")
        assertTrue(vm.commonState.value.cells.isEmpty(), "and so are the cells")
        assertEquals(
            listOf(GridViewMode.CONDENSED, GridViewMode.CONDENSED),
            schedule.monthLoads,
            "each month fetches its own grid - there is no station-wide one to reuse",
        )
    }

    @Test
    fun clearDropsTheBreaksSoAStationSwitchRefetchesThem() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(spots = listOf(placed(10)))))
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()
        assertEquals(1, vm.commonState.value.breaks.size)

        vm.clear(); advanceUntilIdle()
        assertTrue(vm.commonState.value.breaks.isEmpty(), "the breaks belonged to the OLD station")

        vm.loadMonth(2026, 7); advanceUntilIdle()
        assertEquals(2, schedule.monthLoads.size, "the new station's grid is fetched afresh")
    }

    /**
     * "Προβολή κάθε" reloads only the ROWS: the same airings are on screen either
     * way - the view only decides how much empty scaffold is drawn around them.
     */
    @Test
    fun setViewModeReloadsTheRowsButNotTheMonth() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(spots = listOf(placed(10)))))
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
        schedule.monthResult = DataResult.Success(month(cell(spots = emptyList())))
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
        schedule.monthResult = DataResult.Success(month(cell(spots = listOf(placed(10)))))
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()

        vm.removeLast(time = TEST_TIME, date = TEST_DATE)
        advanceUntilIdle()

        assertTrue(placements.removedIds.isEmpty(), "'r' must never delete a placement we did not add")
        assertEquals(1, vm.commonState.value.cells[key]?.spotCount, "the cell is unchanged")
    }

    @Test
    fun addThenRemoveLastRestoresTheCellAndClearsMarkers() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(spots = emptyList())))
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

    /**
     * A reorder happens in the Break Console, so the cell holds its airings by
     * then - and it holds them because the console FETCHED them (they no longer
     * ride in on the month). Hence the loadCommercials in the arrange block: it
     * is not scaffolding, it is how a cell comes to have a list to reorder.
     */
    @Test
    fun reorderWithStaleIdsLeavesTheCellUnchanged() = runTest(testDispatcher) {
        schedule.monthResult = DataResult.Success(month(cell(spots = listOf(placed(10), placed(11)))))
        schedule.stock(TEST_TIME, TEST_DATE, placed(10), placed(11))
        val vm = vm()
        vm.loadMonth(2026, 7); advanceUntilIdle()
        vm.loadCommercials(TEST_TIME, TEST_DATE); advanceUntilIdle()

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
