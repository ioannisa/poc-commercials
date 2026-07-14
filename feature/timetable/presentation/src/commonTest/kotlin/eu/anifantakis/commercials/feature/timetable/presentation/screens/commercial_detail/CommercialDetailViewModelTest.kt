package eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail

import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_DATE
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_TIME
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeReportService
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeStationLogoCache
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeTimetableCommon
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeUserSession
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommonState
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableTestBase
import eu.anifantakis.commercials.core.presentation.grids.BreakSlot
import eu.anifantakis.commercials.core.presentation.grids.CommercialItem
import eu.anifantakis.commercials.core.presentation.grids.FLOW_ROH
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.StationLogoCache
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
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
 * station's breaks live in that same shared state, so the fake feeds the
 * Προηγούμενο/Επόμενο chain (occupied breaks of the day, in air order)
 * without any repository at all.
 *
 * Reordering, the edit permission and the header stats are the ViewModel's
 * job (the screen only renders), so they are covered here rather than in a
 * UI test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommercialDetailViewModelTest : TimetableTestBase() {

    private val key = SchedulerKey(TEST_TIME, TEST_DATE)

    /** A break as the SHARED state carries it (the grid's UI model). It IS a time. */
    private fun slot(hour: Int, minute: Int = 0) = BreakSlot(
        time = LocalTime(hour, minute),
        label = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}",
    )

    private fun item(id: Long, durationSeconds: Int = 30, flow: String = "NORMAL") = CommercialItem(
        id = id,
        clientCode = "CUS$id",
        clientName = "Client $id",
        message = "Spot $id",
        durationSeconds = durationSeconds,
        type = "TV",
        contract = "C$id",
        flow = flow,
    )

    private fun cell(vararg ids: Long) = SchedulerCellData(
        spotCount = ids.size,
        commercials = persistentListOf(*ids.map { item(it) }.toTypedArray()),
    )

    private fun vm(
        common: FakeTimetableCommon,
        time: LocalTime = TEST_TIME,
        role: AppRole = AppRole.NORMAL_USER,
        reportService: ReportService = FakeReportService(),
        logoCache: StationLogoCache = FakeStationLogoCache(),
    ) = CommercialDetailViewModel(
        time = time,
        date = TEST_DATE,
        common = common,
        session = FakeUserSession(role),
        reportService = reportService,
        logoCache = logoCache,
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
    fun moveRowTranslatesIndicesIntoTheNewOrderAndDelegatesUp() = runTest(testDispatcher) {
        val common = FakeTimetableCommon()
        val vm = vm(common)
        common.emit(TimetableCommonState(cells = persistentMapOf(key to cell(10, 11, 12))))
        advanceUntilIdle()

        // drag the LAST row onto the first
        vm.onAction(CommercialDetailIntent.MoveRow(from = 2, to = 0))

        assertEquals(
            listOf(Triple(TEST_TIME, TEST_DATE, listOf(12L, 10L, 11L))),
            common.reorders,
            "the screen must delegate reorder up - never mutate shared state itself",
        )
    }

    @Test
    fun moveRowIgnoresNoOpsAndOutOfBoundsIndices() = runTest(testDispatcher) {
        val common = FakeTimetableCommon()
        val vm = vm(common)
        common.emit(TimetableCommonState(cells = persistentMapOf(key to cell(10, 11))))
        advanceUntilIdle()

        vm.onAction(CommercialDetailIntent.MoveRow(from = 1, to = 1))   // no-op
        vm.onAction(CommercialDetailIntent.MoveRow(from = 0, to = 5))   // past the end
        vm.onAction(CommercialDetailIntent.MoveRow(from = -1, to = 0))  // before the start

        assertTrue(common.reorders.isEmpty(), "bounds are the ViewModel's job, not the grid's")
    }

    @Test
    fun viewOnlyRolesCannotReorder() = runTest(testDispatcher) {
        val common = FakeTimetableCommon()
        val vm = vm(common, role = AppRole.REPORT_VIEWER)
        common.emit(TimetableCommonState(cells = persistentMapOf(key to cell(10, 11))))
        advanceUntilIdle()

        assertTrue(vm.state.canEdit.not(), "a report viewer may browse and print, never edit")
        vm.onAction(CommercialDetailIntent.MoveRow(from = 0, to = 1))

        assertTrue(common.reorders.isEmpty(), "the permission is enforced in the ViewModel")
    }

    @Test
    fun headerStatsSplitTheCellIntoFlowAndExcluded() = runTest(testDispatcher) {
        val common = FakeTimetableCommon()
        val vm = vm(common)

        common.emit(
            TimetableCommonState(
                cells = persistentMapOf(
                    key to SchedulerCellData(
                        commercials = persistentListOf(
                            item(10, durationSeconds = 30, flow = FLOW_ROH),
                            item(11, durationSeconds = 20, flow = FLOW_ROH),
                            item(12, durationSeconds = 15, flow = "NORMAL"),
                        )
                    )
                )
            )
        )
        advanceUntilIdle()

        assertEquals(3, vm.state.totalSpots)
        assertEquals(65, vm.state.totalDuration)
        assertEquals(2, vm.state.flowSpots)
        assertEquals(50, vm.state.flowDuration)
        assertEquals(1, vm.state.excludedSpots)
        assertEquals(15, vm.state.excludedDuration)
    }

    @Test
    fun printBreakRendersTheCellAndSkipsAnEmptyOne() = runTest(testDispatcher) {
        val common = FakeTimetableCommon()
        val reports = FakeReportService()
        val vm = vm(common, reportService = reports)

        vm.onAction(CommercialDetailIntent.PrintBreak)
        advanceUntilIdle()
        assertTrue(reports.printed.isEmpty(), "an empty break has nothing to print")

        common.emit(TimetableCommonState(cells = persistentMapOf(key to cell(10, 11))))
        advanceUntilIdle()
        vm.onAction(CommercialDetailIntent.PrintBreak)
        advanceUntilIdle()

        assertEquals(1, reports.printed.size, "printing is the ViewModel's side effect, not the composable's")
    }

    @Test
    fun previousAndNextWalkTheDaysOccupiedBreaksInAirOrder() = runTest(testDispatcher) {
        val common = FakeTimetableCommon()
        val vm = vm(common)

        // grid: 08:00 (occupied) 10:00 (THIS) 12:00 (EMPTY row) 14:00 (occupied),
        // deliberately out of air order to prove the sort.
        common.emit(
            TimetableCommonState(
                breaks = persistentListOf(slot(14), slot(10), slot(8), slot(12)),
                cells = persistentMapOf(
                    SchedulerKey(LocalTime(8, 0), TEST_DATE) to cell(50),
                    key to cell(10, 11),
                    SchedulerKey(LocalTime(14, 0), TEST_DATE) to cell(90),
                )
            )
        )
        advanceUntilIdle()

        assertEquals("10:00", vm.state.breakLabel, "its own label is PULLED from the shared breaks")
        assertEquals(LocalTime(8, 0), vm.state.previousBreak?.time, "previous = the earlier occupied break")
        assertEquals("08:00", vm.state.previousBreak?.label)
        assertEquals(LocalTime(14, 0), vm.state.nextBreak?.time, "the EMPTY 12:00 row is skipped")
        assertEquals("14:00", vm.state.nextBreak?.label)
    }

    @Test
    fun edgesOfTheDayDisableTheCorrespondingDirection() = runTest(testDispatcher) {
        val common = FakeTimetableCommon()
        val vm = vm(common)

        common.emit(
            TimetableCommonState(
                breaks = persistentListOf(slot(10), slot(14)),
                cells = persistentMapOf(
                    key to cell(10),
                    SchedulerKey(LocalTime(14, 0), TEST_DATE) to cell(90),
                )
            )
        )
        advanceUntilIdle()

        assertNull(vm.state.previousBreak, "first occupied break of the day has no previous")
        assertEquals(LocalTime(14, 0), vm.state.nextBreak?.time)
    }
}
