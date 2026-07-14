package eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable

import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.core.domain.auth.StationAccess
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeFinderRepository
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeReportService
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeStationLogoCache
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeUserSession
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakePartySearchRepository
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeTimetableCommon
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeTimetablePreferences
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_DATE
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_TIME
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommonState
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableTestBase
import eu.anifantakis.commercials.feature.timetable.presentation.screens.cell
import eu.anifantakis.commercials.feature.timetable.presentation.screens.placed
import eu.anifantakis.commercials.core.presentation.global_state.GlobalEffect
import eu.anifantakis.commercials.core.presentation.grids.BreakSlot
import eu.anifantakis.commercials.core.presentation.grids.StableDate
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.toUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import eu.anifantakis.commercials.reports.StationLogoCache
import eu.anifantakis.commercials.reports.models.ReportResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
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
@OptIn(ExperimentalCoroutinesApi::class)
class TimetableViewModelTest : TimetableTestBase() {

    private val finder = FakeFinderRepository()
    private val partySearch = FakePartySearchRepository()
    private val common = FakeTimetableCommon()
    private val reports = FakeReportService()

    // No ScheduleRepository: the breaks and the cells both come from `common`.
    private fun vm(
        prefs: FakeTimetablePreferences = FakeTimetablePreferences(),
        session: FakeUserSession = FakeUserSession(),
        reportService: FakeReportService = reports,
        logoCache: StationLogoCache = FakeStationLogoCache(),
    ) = TimetableViewModel(finder, partySearch, common, prefs, session, reportService, logoCache)

    /** A month with one spot in the 10:00 break - enough to build a payload. */
    private fun aMonthWithOneSpot(): TimetableCommonState {
        val (k, data) = cell(commercials = listOf(placed(10))).toUi()
        return TimetableCommonState(
            breaks = persistentListOf(BreakSlot(time = TEST_TIME, label = "10:00")),
            cells = persistentMapOf(k to data),
        )
    }

    private val key = SchedulerKey(TEST_TIME, TEST_DATE)

    private fun station(id: String, name: String) =
        StationAccess(id = id, name = name, role = AppRole.NORMAL_USER.name)

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

    /**
     * Was `asksTheCommonContractForTheBreaksInsteadOfFetchingThemItself`, which
     * counted a separate `loadBreaks` call. There is only ONE verb now - the rows
     * and the cells arrive together - so the point it was making (the screen owns
     * no ScheduleRepository; it asks the shared owner) rests on that single load.
     */
    @Test
    fun asksTheCommonContractForTheMonthInsteadOfFetchingItItself() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()   // let onStart { loadAll() } settle

        assertEquals(
            listOf(vm.state.year to vm.state.month),
            common.loads,
            "the month's rows AND cells are loaded through the shared owner, in one call",
        )
    }

    /**
     * Was `monthNavigationKeepsTheStationsBreaks`. The rows are the MONTH's now,
     * not the station's, so "keeps them" is exactly what must NOT happen: the one
     * load per month carries them. What survives is the rest of the session -
     * navigating still must not clear().
     */
    @Test
    fun monthNavigationReloadsThroughTheCommonContractWithoutClearing() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()

        vm.onAction(TimetableIntent.NextMonth)
        advanceUntilIdle()

        assertEquals(0, common.clears, "changing month is not a station switch - nothing is wiped")
        assertEquals(2, common.loads.size, "it loads the new month: its rows and its cells")
        assertEquals(vm.state.year to vm.state.month, common.loads.last(), "the month it navigated to")
    }

    @Test
    fun viewModeChangedDelegatesToTheCommonContract() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val loadsBefore = common.loads.size

        vm.onAction(TimetableIntent.ViewModeChanged(GridViewMode.HOURLY))
        advanceUntilIdle()

        assertEquals(listOf(GridViewMode.HOURLY), common.viewModes, "'Προβολή κάθε' goes up to the shared owner")
        assertEquals(loadsBefore, common.loads.size, "only the rows change - the month is not re-fetched")
    }

    @Test
    fun sessionFactsSeedTheStateSoTheChromeNeverTouchesUserSession() = runTest(testDispatcher) {
        val session = FakeUserSession(
            role = AppRole.REPORT_VIEWER,
            displayName = "Maria",
            stations = listOf(station("crete-tv", "Crete TV"), station("crete-fm", "Crete FM")),
        )
        val vm = vm(session = session)
        advanceUntilIdle()

        assertFalse(vm.state.canEdit, "a report viewer browses and prints, never edits")
        assertEquals("Maria", vm.state.displayName)
        assertEquals(AppRole.REPORT_VIEWER, vm.state.role)
        assertEquals(listOf("crete-tv", "crete-fm"), vm.state.stations.map { it.id })
        assertEquals("crete-tv", vm.state.selectedStation?.id)
    }

    @Test
    fun selectStationDelegatesToTheSessionAndReloadsWithTheNewRole() = runTest(testDispatcher) {
        val session = FakeUserSession(
            role = AppRole.NORMAL_USER,
            stations = listOf(station("crete-tv", "Crete TV"), station("crete-fm", "Crete FM")),
        )
        val vm = vm(session = session)
        advanceUntilIdle()
        val loadsAfterStartup = common.loads.size

        vm.onAction(TimetableIntent.SelectStation("crete-fm"))
        advanceUntilIdle()

        assertEquals("crete-fm", vm.state.selectedStation?.id, "the chrome follows the session")
        assertEquals(1, common.clears, "a station switch drops the previous station's data")
        assertEquals(loadsAfterStartup + 1, common.loads.size, "and refetches the month")
    }

    @Test
    fun aSessionChangeOutsideThisScreenAlsoReloads() = runTest(testDispatcher) {
        val session = FakeUserSession()
        val vm = vm(session = session)
        advanceUntilIdle()
        assertEquals(0, common.clears, "seeding the facts must NOT count as a change")

        session.bumpRevision()   // e.g. a re-login elsewhere in the app
        advanceUntilIdle()

        assertEquals(1, common.clears, "the revision bridge lives in the ViewModel, not a LaunchedEffect")
        assertEquals(2, common.loads.size, "the new session reloads the month - its rows and its cells")
    }

    @Test
    fun dailyTotalsAreDerivedFromTheSharedCells() = runTest(testDispatcher) {
        val vm = vm()
        val (k, data) = cell(commercials = listOf(placed(10), placed(11))).toUi()

        common.emit(TimetableCommonState(cells = persistentMapOf(k to data)))
        advanceUntilIdle()

        assertEquals(
            2,
            vm.state.dailyTotals[StableDate(TEST_DATE)]?.spotCount,
            "the Σύνολα footer is state, not a calculation inside a composable",
        )
    }

    @Test
    fun printBreakAssemblesThePayloadAndUsesTheReportService() = runTest(testDispatcher) {
        val vm = vm()
        val (k, data) = cell(commercials = listOf(placed(10))).toUi()
        common.emit(
            TimetableCommonState(
                breaks = persistentListOf(BreakSlot(time = TEST_TIME, label = "10:00")),
                cells = persistentMapOf(k to data),
            )
        )
        advanceUntilIdle()

        vm.onAction(TimetableIntent.PrintBreak(time = TEST_TIME, date = TEST_DATE))
        advanceUntilIdle()

        assertEquals(1, reports.printed.size, "printing is the ViewModel's side effect, not the composable's")
    }

    @Test
    fun printingAnUnknownBreakIsANoOp() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()

        // 23:55 is no row of this month - nothing aired there.
        val unknown = LocalTime(23, 55)
        vm.onAction(TimetableIntent.PrintBreak(time = unknown, date = TEST_DATE))
        vm.onAction(TimetableIntent.PrintBreakMonth(time = unknown))
        advanceUntilIdle()

        assertTrue(reports.printed.isEmpty(), "no break, no payload - and no crash")
    }

    // ── the report toolbar's three actions (the toolbar itself is stateless) ──

    @Test
    fun reportsAvailableMirrorsThePlatformCapability() = runTest(testDispatcher) {
        val vm = vm(reportService = FakeReportService(available = false))
        advanceUntilIdle()

        assertFalse(vm.state.reportsAvailable, "mobile targets cannot generate reports")
    }

    @Test
    fun previewMonthBuildsThePayloadsAndCallsTheService() = runTest(testDispatcher) {
        val vm = vm()
        common.emit(aMonthWithOneSpot())
        advanceUntilIdle()

        vm.onAction(TimetableIntent.PreviewMonth)
        advanceUntilIdle()

        assertEquals(1, reports.previewed.size, "the ViewModel assembles the payload, not the toolbar")
        assertTrue(reports.previewed.single().isNotEmpty())
        assertFalse(vm.state.reportBusy, "the toolbar is released when the action finishes")
    }

    @Test
    fun exportMonthPdfNamesTheFileAfterTheVisibleMonth() = runTest(testDispatcher) {
        val vm = vm()
        common.emit(aMonthWithOneSpot())
        advanceUntilIdle()

        vm.onAction(TimetableIntent.ExportMonthPdf)
        advanceUntilIdle()

        val expected = "ProgramFlow_${vm.state.year}-${vm.state.month.toString().padStart(2, '0')}.pdf"
        assertEquals(listOf(expected), reports.exported)
    }

    @Test
    fun anEmptyMonthNeverReachesTheReportService() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val effects = mutableListOf<GlobalEffect>()
        backgroundScope.launch { globalContainer.effects.collect { effects += it } }

        vm.onAction(TimetableIntent.PrintMonth)
        advanceUntilIdle()

        assertTrue(reports.printed.isEmpty(), "nothing to print - the service is never asked")
        assertEquals(
            1,
            effects.count { it is GlobalEffect.SnackBarMessage },
            "the outcome surfaces once, through the global snackbar the toolbar no longer owns",
        )
        assertFalse(vm.state.reportBusy)
    }

    @Test
    fun aCancelledSaveDialogStillReleasesTheToolbar() = runTest(testDispatcher) {
        val reportService = FakeReportService().apply { result = ReportResult.Cancelled }
        val vm = vm(reportService = reportService)
        common.emit(aMonthWithOneSpot())
        advanceUntilIdle()
        val effects = mutableListOf<GlobalEffect>()
        backgroundScope.launch { globalContainer.effects.collect { effects += it } }

        vm.onAction(TimetableIntent.ExportMonthPdf)
        advanceUntilIdle()

        assertFalse(vm.state.reportBusy, "a cancelled dialog must not leave the buttons disabled")
        assertEquals(1, effects.count { it is GlobalEffect.SnackBarMessage })
    }

    @Test
    fun addSpotAtWithoutAnArmedSpotDoesNothing() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()   // let loadAll settle

        // No FinderSpotSelected first -> 'a' must be a no-op.
        vm.onAction(TimetableIntent.AddSpotAt(time = TEST_TIME, date = TEST_DATE))

        assertTrue(common.adds.isEmpty(), "'a' does nothing until a spot is armed via Εύρεση")
    }

    @Test
    fun addSpotAtWithAnArmedSpotDelegatesToTheCommonContract() = runTest(testDispatcher) {
        val vm = vm()
        vm.onAction(TimetableIntent.FinderSpotSelected(ContractLineSpot(spotId = 42, description = "x", durationSeconds = 30, placements = 1)))

        vm.onAction(TimetableIntent.AddSpotAt(time = TEST_TIME, date = TEST_DATE))

        assertEquals(
            listOf(Triple(42L, TEST_TIME, TEST_DATE)),
            common.adds,
            "the armed spot's id is delegated up to common.add - the screen never persists itself",
        )
    }

    @Test
    fun removeLastAtDelegatesToTheCommonContract() = runTest(testDispatcher) {
        val vm = vm()

        vm.onAction(TimetableIntent.RemoveLastAt(time = TEST_TIME, date = TEST_DATE))

        assertEquals(listOf(TEST_TIME to TEST_DATE), common.removes)
        assertTrue(common.adds.isEmpty())
    }
}
