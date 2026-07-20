package eu.anifantakis.commercials.feature.timetable.presentation.reports

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.toUi
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeReportService
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeScheduleRepository
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeStationLogoCache
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_DATE
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_TIME
import eu.anifantakis.commercials.feature.timetable.presentation.screens.cell
import eu.anifantakis.commercials.feature.timetable.presentation.screens.placed
import eu.anifantakis.commercials.grids.BreakSlot
import eu.anifantakis.commercials.reports.models.ReportResult
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reports controller, tested WITHOUT a ViewModel or a Koin container -
 * which is the point of it returning a [ReportOutcome] instead of calling
 * showSnackbar. The ViewModel's own tests still cover the busy flag and the
 * fact that the outcome reaches the one snackbar.
 */
class ScheduleReportsControllerTest {

    private val schedule = FakeScheduleRepository()
    private val service = FakeReportService()

    private fun controller() = ScheduleReportsController(schedule, service, FakeStationLogoCache())

    /** A month with one airing in the 10:00 break, as the grid would hold it. */
    private fun context(): ReportContext {
        schedule.stock(TEST_TIME, TEST_DATE, placed(10))
        val (key, data) = cell(spots = listOf(placed(10))).toUi()
        return ReportContext(
            year = 2026,
            month = 7,
            breaks = persistentListOf(BreakSlot(time = TEST_TIME, label = "10:00")),
            cells = persistentMapOf(key to data),
        )
    }

    @Test
    fun aDayReportFetchesItsOwnSliceAndPrints() = runTest {
        val outcome = controller().printDay(context(), TEST_DATE)

        assertEquals(ReportOutcome.Silent, outcome, "a print job has nothing to say")
        assertEquals(1, service.printed.size)
        assertEquals(
            listOf(TEST_DATE),
            schedule.commercialLoads.map { it.date },
            "ONE day's airings - the grid's cells carry none, and the month is not fetched",
        )
    }

    @Test
    fun aBreakReportNarrowsToThatBreak() = runTest {
        val outcome = controller().printBreak(context(), TEST_TIME, TEST_DATE)

        assertEquals(ReportOutcome.Silent, outcome)
        assertEquals(listOf(TEST_TIME), schedule.commercialLoads.map { it.time })
    }

    /** A break the month does not have: no payload, no fetch, no crash. */
    @Test
    fun aMissingBreakIsSilentAndFetchesNothing() = runTest {
        val outcome = controller().printBreak(context(), kotlinx.datetime.LocalTime(23, 55), TEST_DATE)

        assertEquals(ReportOutcome.Silent, outcome)
        assertTrue(service.printed.isEmpty())
        assertTrue(schedule.commercialLoads.isEmpty())
    }

    @Test
    fun theMonthReportRoutesToTheModeAsked() = runTest {
        val ctx = context()

        controller().runMonth(ctx, MonthReportMode.PREVIEW)
        assertEquals(1, service.previewed.size)

        controller().runMonth(ctx, MonthReportMode.PRINT)
        assertEquals(1, service.printed.size)

        controller().runMonth(ctx, MonthReportMode.EXPORT_PDF)
        assertEquals(listOf("ProgramFlow_2026-07.pdf"), service.exported, "the name carries the month, zero-padded")
    }

    /** An empty month never reaches the service - it says so instead. */
    @Test
    fun anEmptyMonthNotifiesAndPrintsNothing() = runTest {
        val empty = ReportContext(2026, 7, persistentListOf(), persistentMapOf())

        val outcome = controller().runMonth(empty, MonthReportMode.PRINT)

        assertTrue(outcome is ReportOutcome.Notify)
        assertTrue(service.printed.isEmpty())
    }

    /**
     * The engine's own text is authoritative - a Success message and an Error
     * message travel VERBATIM, never translated.
     */
    @Test
    fun theEnginesOwnMessageIsPassedThroughUntranslated() = runTest {
        service.result = ReportResult.Error("printer offline")

        val outcome = controller().runMonth(context(), MonthReportMode.PRINT)

        assertEquals(ReportOutcome.Notify(UiText.Dynamic("printer offline")), outcome)
    }

    @Test
    fun aCancelledDialogIsReportedAsSuch() = runTest {
        service.result = ReportResult.Cancelled

        val outcome = controller().runMonth(context(), MonthReportMode.PRINT)

        assertTrue(outcome is ReportOutcome.Notify, "cancelling still tells the user something")
    }

    /** A failed slice fetch becomes the message, not a silent empty report. */
    @Test
    fun aFailedFetchIsReportedAndNothingIsPrinted() = runTest {
        schedule.commercialsFailure = DataError.Network.NO_INTERNET

        val outcome = controller().runMonth(context(), MonthReportMode.PRINT)

        assertTrue(outcome is ReportOutcome.Notify)
        assertTrue(service.printed.isEmpty(), "a report built on a failed fetch must never be sent")
    }
}
