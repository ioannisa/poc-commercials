package eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_DATE
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeTimetableCommon
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommonState
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableTestBase
import eu.anifantakis.commercials.core.presentation.grids.CommercialItem
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.domain.model.MonthSchedule
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The detail screen's own ViewModel against a tiny fake of the
 * [TimetableCommon] CONTRACT - proving the two star-topology legs: state
 * flows DOWN (its cell's commercials, merged from commonState) and the
 * reorder command goes UP (delegated to the contract). No concrete
 * CommonViewModel - the contract is exactly what makes this cheap. The
 * repository fake feeds the station grid the Προηγούμενο/Επόμενο paging
 * walks (occupied breaks of the day, in air order).
 */
class CommercialDetailViewModelTest : TimetableTestBase() {

    private val key = SchedulerKey(1L, TEST_DATE)

    private class FakeScheduleRepository(
        private val breaks: List<BreakSlotInfo> = emptyList(),
    ) : ScheduleRepository {
        override suspend fun getBreaks(): DataResult<List<BreakSlotInfo>, DataError.Network> =
            DataResult.Success(breaks)

        override suspend fun getMonth(year: Int, month: Int): DataResult<MonthSchedule, DataError.Network> =
            DataResult.Success(MonthSchedule(year, month, emptyList()))
    }

    private fun slot(id: Long, hour: Int, minute: Int = 0) = BreakSlotInfo(
        id = id, hour = hour, minute = minute,
        label = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}",
        zone = "DEFAULT", zoneColorArgb = 0,
    )

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

    private fun cell(vararg ids: Long) = SchedulerCellData(
        spotCount = ids.size,
        commercials = persistentListOf(*ids.map { item(it) }.toTypedArray()),
    )

    private fun vm(
        common: FakeTimetableCommon,
        breaks: List<BreakSlotInfo> = emptyList(),
        breakId: Long = 1,
    ) = CommercialDetailViewModel(
        breakId = breakId,
        date = TEST_DATE,
        common = common,
        scheduleRepository = FakeScheduleRepository(breaks),
    )

    @Test
    fun observesItsCellsCommercialsFromCommonState() = runTest(testDispatcher) {
        val common = FakeTimetableCommon()
        val vm = vm(common)

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
        val vm = vm(common)

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
        val vm = vm(common)

        vm.onAction(CommercialDetailIntent.Reorder(orderedIds = listOf(11L, 10L)))

        assertEquals(
            listOf(Triple(1L, TEST_DATE, listOf(11L, 10L))),
            common.reorders,
            "the screen must delegate reorder up - never mutate shared state itself",
        )
    }

    @Test
    fun previousAndNextWalkTheDaysOccupiedBreaksInAirOrder() = runTest(testDispatcher) {
        val common = FakeTimetableCommon()
        // grid: 08:00(#5, occupied) 10:00(#1, THIS) 12:00(#7, EMPTY) 14:00(#9, occupied)
        val vm = vm(common, breaks = listOf(slot(9, 14), slot(1, 10), slot(5, 8), slot(7, 12)))

        common.emit(
            TimetableCommonState(
                cells = persistentMapOf(
                    SchedulerKey(5L, TEST_DATE) to cell(50),
                    key to cell(10, 11),
                    SchedulerKey(9L, TEST_DATE) to cell(90),
                )
            )
        )
        advanceUntilIdle()

        assertEquals(5L, vm.state.previousBreak?.breakId, "previous = the earlier occupied break")
        assertEquals("08:00", vm.state.previousBreak?.label)
        assertEquals(9L, vm.state.nextBreak?.breakId, "the EMPTY 12:00 break is skipped")
        assertEquals("14:00", vm.state.nextBreak?.label)
    }

    @Test
    fun edgesOfTheDayDisableTheCorrespondingDirection() = runTest(testDispatcher) {
        val common = FakeTimetableCommon()
        val vm = vm(common, breaks = listOf(slot(1, 10), slot(9, 14)))

        common.emit(
            TimetableCommonState(
                cells = persistentMapOf(
                    key to cell(10),
                    SchedulerKey(9L, TEST_DATE) to cell(90),
                )
            )
        )
        advanceUntilIdle()

        assertNull(vm.state.previousBreak, "first occupied break of the day has no previous")
        assertEquals(9L, vm.state.nextBreak?.breakId)
    }
}
