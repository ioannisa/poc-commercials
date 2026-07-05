package eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable

import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeFinderRepository
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakePartySearchRepository
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeScheduleRepository
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeTimetableCommon
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeTimetablePreferences
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_DATE
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommonState
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableTestBase
import eu.anifantakis.commercials.feature.timetable.presentation.screens.cell
import eu.anifantakis.commercials.feature.timetable.presentation.screens.placed
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.toUi
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The grid SCREEN ViewModel - now unit-testable because its persistence
 * runs behind the [eu.anifantakis.commercials.feature.timetable.domain.TimetablePreferences]
 * seam (a fake, not a concrete KSafe). Proves the screen-local concerns
 * (display toggle, finder-armed 'a') and the star-topology delegation to the
 * [eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommon] contract.
 */
class TimetableViewModelTest : TimetableTestBase() {

    private val schedule = FakeScheduleRepository()
    private val finder = FakeFinderRepository()
    private val partySearch = FakePartySearchRepository()
    private val common = FakeTimetableCommon()

    private fun vm(prefs: FakeTimetablePreferences = FakeTimetablePreferences()) =
        TimetableViewModel(schedule, finder, partySearch, common, prefs)

    private val key = SchedulerKey(1L, TEST_DATE)

    @Test
    fun showSpotTimesInitialValueComesFromPrefs() = runTest(testDispatcher) {
        val vm = vm(FakeTimetablePreferences(showSpotTimes = true))
        assertTrue(vm.state.showSpotTimes, "the persisted display mode seeds the initial state")
    }

    @Test
    fun toggleShowTimesFlipsStateAndPersists() = runTest(testDispatcher) {
        val prefs = FakeTimetablePreferences(showSpotTimes = false)
        val vm = vm(prefs)

        vm.onAction(TimetableIntent.ToggleShowTimes)
        advanceUntilIdle()

        assertTrue(vm.state.showSpotTimes, "the '#' toggle flips the on-screen mode")
        assertTrue(prefs.showSpotTimes, "and persists it so it survives a restart")
    }

    @Test
    fun mergesCommonStateCellsIntoScreenState() = runTest(testDispatcher) {
        val vm = vm()
        val (k, data) = cell(commercials = listOf(placed(10), placed(11))).toUi()

        common.emit(TimetableCommonState(cells = persistentMapOf(k to data)))
        advanceUntilIdle()

        assertEquals(2, vm.state.cells[key]?.spotCount, "the grid renders straight from the shared cells")
    }

    @Test
    fun addSpotAtWithoutAnArmedSpotDoesNothing() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()   // let loadAll settle

        // No FinderSpotSelected first -> 'a' must be a no-op.
        vm.onAction(TimetableIntent.AddSpotAt(breakId = 1, date = TEST_DATE))

        assertTrue(common.adds.isEmpty(), "'a' does nothing until a spot is armed via Εύρεση")
    }

    @Test
    fun addSpotAtWithAnArmedSpotDelegatesToTheCommonContract() = runTest(testDispatcher) {
        val vm = vm()
        vm.onAction(TimetableIntent.FinderSpotSelected(ContractLineSpot(spotId = 42, description = "x", durationSeconds = 30, placements = 1)))

        vm.onAction(TimetableIntent.AddSpotAt(breakId = 1, date = TEST_DATE))

        assertEquals(
            listOf(Triple(42L, 1L, TEST_DATE)),
            common.adds,
            "the armed spot's id is delegated up to common.add - the screen never persists itself",
        )
    }

    @Test
    fun removeLastAtDelegatesToTheCommonContract() = runTest(testDispatcher) {
        val vm = vm()

        vm.onAction(TimetableIntent.RemoveLastAt(breakId = 1, date = TEST_DATE))

        assertEquals(listOf(1L to TEST_DATE), common.removes)
        assertTrue(common.adds.isEmpty())
    }
}
