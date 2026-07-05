package eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail

import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_DATE
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommon
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommonState
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableTestBase
import eu.anifantakis.commercials.core.presentation.grids.CommercialItem
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** A 10-line fake of the contract - the whole point of depending on the interface. */
private class FakeTimetableCommon : TimetableCommon {
    private val _commonState = MutableStateFlow(TimetableCommonState())
    override val commonState: StateFlow<TimetableCommonState> = _commonState.asStateFlow()

    fun emit(state: TimetableCommonState) { _commonState.value = state }

    val reorders = mutableListOf<Triple<Long, LocalDate, List<Long>>>()

    override fun clear() {}
    override fun loadMonth(year: Int, month: Int) {}
    override fun add(spotId: Long, breakId: Long, date: LocalDate) {}
    override fun removeLast(breakId: Long, date: LocalDate) {}
    override fun reorder(breakId: Long, date: LocalDate, orderedIds: List<Long>) {
        reorders += Triple(breakId, date, orderedIds)
    }
}
