package eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable

import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.core.domain.auth.StationAccess
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.presentation.screens.CommercialsQuery
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_CLOCK
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeReportService
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeScheduleRepository
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeStationLogoCache
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeUserSession
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeProgramsRepository
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeTimetableCommon
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeTimetablePreferences
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
import eu.anifantakis.commercials.feature.timetable.domain.model.ScheduleFilter
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_DATE
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_TIME
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommonState
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableTestBase
import eu.anifantakis.commercials.feature.timetable.presentation.screens.cell
import eu.anifantakis.commercials.feature.timetable.presentation.screens.placed
import eu.anifantakis.commercials.core.presentation.global_state.GlobalEffect
import eu.anifantakis.commercials.grids.BreakSlot
import eu.anifantakis.commercials.grids.StableDate
import eu.anifantakis.commercials.grids.SchedulerKey
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

    private val common = FakeTimetableCommon()
    private val reports = FakeReportService()

    /**
     * The grid still takes nothing from a ScheduleRepository - its rows and its
     * cells come from `common`. The REPORTS do: the cells they print carry no
     * airings (the grid draws counts), so each fetches the slice it is about to
     * print. That is the only reason this fake is here.
     */
    private val schedule = FakeScheduleRepository()
    private val programs = FakeProgramsRepository()

    private fun vm(
        prefs: FakeTimetablePreferences = FakeTimetablePreferences(),
        session: FakeUserSession = FakeUserSession(),
        reportService: FakeReportService = reports,
        logoCache: StationLogoCache = FakeStationLogoCache(),
    ) = TimetableViewModel(
        schedule, programs, common, prefs, session, reportService, logoCache, TEST_CLOCK
    )

    /**
     * A month with one spot in the 10:00 break - enough to build a payload.
     *
     * "One spot" is now split across two places, exactly as production splits it:
     * the grid's cell says A spot airs there (spotCount = 1, no airing), and the
     * REPOSITORY holds which one. A report that does not go and fetch it prints
     * nothing - which is what the report tests below would then catch.
     */
    private fun aMonthWithOneSpot(): TimetableCommonState {
        schedule.stock(TEST_TIME, TEST_DATE, placed(10))
        val (k, data) = cell(spots = listOf(placed(10))).toUi()
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
        val (k, data) = cell(spots = listOf(placed(10), placed(11))).toUi()

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

    // ── «Προβολή Βάσει…» - the Show based on… radio group ──────────────

    @Test
    fun showBasedOnProgramArmsTheFilterWithTheSelectedProgramme() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onAction(TimetableIntent.SelectProgram(7))
        vm.onAction(TimetableIntent.ShowBasedOnChanged(ShowBasedOn.PROGRAM))
        advanceUntilIdle()

        assertEquals(ShowBasedOn.PROGRAM, vm.state.showBasedOn)
        assertEquals(
            ScheduleFilter.ByProgram(7),
            common.filters.last(),
            "the radio reads its subject from the «Τύποι Προγράμματος» dropdown and hands it to the shared owner",
        )
    }

    @Test
    fun showBasedOnWithoutItsSubjectFiltersNothingAndHints() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val effects = mutableListOf<GlobalEffect>()
        backgroundScope.launch { globalContainer.effects.collect { effects += it } }

        vm.onAction(TimetableIntent.ShowBasedOnChanged(ShowBasedOn.CUSTOMER))
        advanceUntilIdle()

        assertEquals(null, common.filters.last(), "no party selected yet - armed, but the grid stays unfiltered")
        assertEquals(
            1,
            effects.count { it is GlobalEffect.SnackBarMessage },
            "and the hint says where the subject comes from (the Εύρεση console)",
        )
    }

    /** The mode can be armed FIRST: the filter lands the moment its subject does - and leaves with it. */
    @Test
    fun armedMessageModeFollowsTheSpotSelection() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onAction(TimetableIntent.ShowBasedOnChanged(ShowBasedOn.MESSAGE))
        advanceUntilIdle()
        assertEquals(null, common.filters.last())

        vm.onAction(
            TimetableIntent.FinderSpotSelected(
                ContractLineSpot(spotId = 42, description = "x", durationSeconds = 30, placements = 1)
            )
        )
        advanceUntilIdle()
        assertEquals(ScheduleFilter.BySpot(42), common.filters.last(), "arming the spot lands the filter")

        vm.onAction(TimetableIntent.ClearFinder)
        advanceUntilIdle()
        assertEquals(null, common.filters.last(), "clearing the finder takes the subject - and the scope - away")
    }

    @Test
    fun showBasedOnAllDisarmsAnActiveFilter() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onAction(TimetableIntent.SelectProgram(7))
        vm.onAction(TimetableIntent.ShowBasedOnChanged(ShowBasedOn.PROGRAM))
        advanceUntilIdle()
        assertEquals(ScheduleFilter.ByProgram(7), common.filters.last())

        vm.onAction(TimetableIntent.ShowBasedOnChanged(ShowBasedOn.ALL))
        advanceUntilIdle()

        assertEquals(null, common.filters.last(), "Όλα hands a null scope up - the grid shows everything again")
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
        val (k, data) = cell(spots = listOf(placed(10), placed(11))).toUi()

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
        common.emit(aMonthWithOneSpot())
        advanceUntilIdle()

        vm.onAction(TimetableIntent.PrintBreak(time = TEST_TIME, date = TEST_DATE))
        advanceUntilIdle()

        assertEquals(1, reports.printed.size, "printing is the ViewModel's side effect, not the composable's")
        assertEquals(
            listOf(CommercialsQuery(vm.state.year, vm.state.month, TEST_DATE, TEST_TIME)),
            schedule.commercialLoads,
            "a break report fetches exactly its own cell's airings",
        )
    }

    /**
     * The whole point of the split, guarded: the grid's cells carry an aggregate
     * and NOTHING else, so a report that read them would print an empty page. It
     * must go and fetch the airings for the slice it is about to print - here, a
     * day - and it must ask for THAT day, not for the month.
     */
    @Test
    fun printDayFetchesItsSlicesAiringsInsteadOfReadingThemOffTheGrid() = runTest(testDispatcher) {
        val vm = vm()
        // The grid as it really is: 10:00 has one spot in it, and the cell does
        // not know which spot. Only the repository does.
        schedule.stock(TEST_TIME, TEST_DATE, placed(10))
        val (k, data) = cell(spots = listOf(placed(10))).toUi()
        common.emit(
            TimetableCommonState(
                breaks = persistentListOf(BreakSlot(time = TEST_TIME, label = "10:00")),
                cells = persistentMapOf(k to data),
            )
        )
        advanceUntilIdle()
        assertTrue(vm.state.cells[key]?.commercials?.isEmpty() == true, "the grid holds no airings to read")

        vm.onAction(TimetableIntent.PrintDay(date = TEST_DATE))
        advanceUntilIdle()

        assertEquals(
            listOf(CommercialsQuery(vm.state.year, vm.state.month, TEST_DATE, null)),
            schedule.commercialLoads,
            "the day report asks for the DAY's airings - one slice, not the month's 13,009",
        )
        assertEquals(1, reports.printed.size, "and prints them - a payload it could not have built from the grid")
        assertTrue(reports.printed.single().isNotEmpty())
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
        assertEquals(0, schedule.commercialsFetches, "and no airings are fetched for a break that does not exist")
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
        // The target cell is PAINTED (its break has a programme) - adding into
        // it needs no selection; the spot will inherit the break's own.
        common.emit(TimetableCommonState(cells = persistentMapOf(cell(programName = "News").toUi())))
        advanceUntilIdle()
        vm.onAction(TimetableIntent.FinderSpotSelected(ContractLineSpot(spotId = 42, description = "x", durationSeconds = 30, placements = 1)))

        vm.onAction(TimetableIntent.AddSpotAt(time = TEST_TIME, date = TEST_DATE))

        assertEquals(
            listOf(Triple(42L, TEST_TIME, TEST_DATE)),
            common.adds,
            "the armed spot's id is delegated up to common.add - the screen never persists itself",
        )
    }

    @Test
    fun addSpotAtOnAWhiteCellWithoutAProgrammeIsRefused() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onAction(TimetableIntent.FinderSpotSelected(ContractLineSpot(spotId = 42, description = "x", durationSeconds = 30, placements = 1)))

        // No cell at (TEST_TIME, TEST_DATE) and no selected programme: the
        // first spot into a white cell PAINTS it - it needs a Τύπος
        // Προγράμματος, the legacy rule.
        vm.onAction(TimetableIntent.AddSpotAt(time = TEST_TIME, date = TEST_DATE))

        assertTrue(common.adds.isEmpty(), "a white cell without a selected programme must refuse the add")
    }

    @Test
    fun addSpotAtOnAnUnpaintedCellWithoutAProgrammeIsRefused() = runTest(testDispatcher) {
        val vm = vm()
        // The cell EXISTS but is UNPAINTED (a «Πρόσθεση νέου διαλείμματος» row's
        // cell): still white - the first spot must carry a programme.
        common.emit(TimetableCommonState(cells = persistentMapOf(cell(programName = null).toUi())))
        advanceUntilIdle()
        vm.onAction(TimetableIntent.FinderSpotSelected(ContractLineSpot(spotId = 42, description = "x", durationSeconds = 30, placements = 1)))

        vm.onAction(TimetableIntent.AddSpotAt(time = TEST_TIME, date = TEST_DATE))

        assertTrue(common.adds.isEmpty(), "an unpainted cell is still white - no programme, no add")
    }

    @Test
    fun addSpotAtOnAWhiteCellCarriesTheSelectedProgramme() = runTest(testDispatcher) {
        programs.programs = listOf(
            eu.anifantakis.commercials.feature.timetable.domain.model.Program(id = 7, name = "News", colorArgb = null)
        )
        val vm = vm()
        advanceUntilIdle()   // loadPrograms lands
        vm.onAction(TimetableIntent.SelectProgram(7))
        vm.onAction(TimetableIntent.FinderSpotSelected(ContractLineSpot(spotId = 42, description = "x", durationSeconds = 30, placements = 1)))

        vm.onAction(TimetableIntent.AddSpotAt(time = TEST_TIME, date = TEST_DATE))

        assertEquals(listOf(Triple(42L, TEST_TIME, TEST_DATE)), common.adds)
        assertEquals(
            listOf<Long?>(7L),
            common.addProgramIds,
            "the first spot into a white cell rides on the selected programme - it paints the break",
        )
    }

    @Test
    fun addBreakAddsARowWithoutNeedingAProgramme() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()
        // NO programme selected on purpose: the button only adds a ROW; the
        // programme enters later, with the first spot into a white cell.
        vm.onAction(TimetableIntent.NewBreakTimeChanged("23:55"))

        vm.onAction(TimetableIntent.AddBreak)

        assertEquals(
            listOf(LocalTime(23, 55) to selectedDayOf(vm)),
            common.breakCreates,
            "the typed ΩΩ:ΛΛ becomes an unpainted break holding the row",
        )
    }

    @Test
    fun addBreakIsANoOpWhenTheTimeAlreadyHoldsARow() = runTest(testDispatcher) {
        val vm = vm()
        common.emit(
            TimetableCommonState(breaks = persistentListOf(BreakSlot(time = LocalTime(23, 55), label = "23:55")))
        )
        advanceUntilIdle()
        vm.onAction(TimetableIntent.NewBreakTimeChanged("23:55"))

        vm.onAction(TimetableIntent.AddBreak)

        assertTrue(common.breakCreates.isEmpty(), "a time that already holds a row adds nothing")
    }

    /** The AddBreak row anchors on the focused day; with no selection that is day 1. */
    private fun selectedDayOf(vm: TimetableViewModel) =
        kotlinx.datetime.LocalDate(vm.state.year, vm.state.month, vm.state.selectedColumn + 1)

    @Test
    fun removeLastAtDelegatesToTheCommonContract() = runTest(testDispatcher) {
        val vm = vm()

        vm.onAction(TimetableIntent.RemoveLastAt(time = TEST_TIME, date = TEST_DATE))

        assertEquals(listOf(TEST_TIME to TEST_DATE), common.removes)
        assertTrue(common.adds.isEmpty())
    }
}
