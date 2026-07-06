package eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail

import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_DATE
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeTimetableCommon
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommonState
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableTestBase
import eu.anifantakis.commercials.core.presentation.grids.CommercialItem
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The detail screen's own ViewModel against a tiny fake of the
 * [TimetableCommon] CONTRACT - proving the two star-topology legs: state
 * flows DOWN (its cell's commercials, merged from commonState) and the
 * reorder command goes UP (delegated to the contract). No repositories, no
 * concrete CommonViewModel - the contract is exactly what makes this cheap.
 */
class CommercialDetailViewModelTest : TimetableTestBase() {

    private val key = SchedulerKey(1L, TEST_DATE)

    private fun item(id: Long) = CommercialItem(
        id = id,
        clientCode = "CUS$id",
        clientName = "Client $id",
        message = "Spot $id",
        durationSeconds = 30,
        type = "TV",
        contract = "C$id",
        flow = "NORMAL",
    )

    @Test
    fun observesItsCellsCommercialsFromCommonState() = runTest(testDispatcher) {
        val common = FakeTimetableCommon()
        val vm = CommercialDetailViewModel(breakId = 1, date = TEST_DATE, common = common)

        assertTrue(vm.state.commercials.isEmpty(), "empty before the flow loads anything")

        common.emit(
            TimetableCommonState(
                cells = persistentMapOf(
                    key to SchedulerCellData(commercials = persistentListOf(item(10), item(11)))
                )
            )
        )
        advanceUntilIdle()

        assertEquals(listOf(10L, 11L), vm.state.commercials.map { it.id })
    }

    @Test
    fun surfacesTheBreaksProgrammeNameFromItsCell() = runTest(testDispatcher) {
        val common = FakeTimetableCommon()
        val vm = CommercialDetailViewModel(breakId = 1, date = TEST_DATE, common = common)

        assertEquals(null, vm.state.programName, "no programme before the flow loads a cell")

        common.emit(
            TimetableCommonState(
                cells = persistentMapOf(
                    key to SchedulerCellData(
                        programName = "MOVIE TIME",
                        commercials = persistentListOf(item(10)),
                    )
                )
            )
        )
        advanceUntilIdle()

        assertEquals("MOVIE TIME", vm.state.programName)
    }

    @Test
    fun reorderIntentDelegatesToTheCommonContract() = runTest(testDispatcher) {
        val common = FakeTimetableCommon()
        val vm = CommercialDetailViewModel(breakId = 1, date = TEST_DATE, common = common)

        vm.onAction(CommercialDetailIntent.Reorder(orderedIds = listOf(11L, 10L)))

        assertEquals(
            listOf(Triple(1L, TEST_DATE, listOf(11L, 10L))),
            common.reorders,
            "the screen must delegate reorder up - never mutate shared state itself",
        )
    }
}

