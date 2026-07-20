package eu.anifantakis.commercials.feature.timetable.presentation.screens.spot_finder

import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.presentation.global_state.GlobalEffect
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeFinderRepository
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakePartySearchRepository
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeTimetableCommon
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Εύρεση window's ViewModel: the search machinery is screen-local, the
 * SELECTION goes up through the [FakeTimetableCommon] contract and comes
 * back down merged - the star topology both directions, with fakes only
 * (mandatory in KMP).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotFinderViewModelTest : TimetableTestBase() {

    private val finder = FakeFinderRepository()
    private val partySearch = FakePartySearchRepository()
    private val common = FakeTimetableCommon()

    private fun vm() = SpotFinderViewModel(finder, partySearch, common)

    private fun party(code: String = "CUS1", name: String = "Aegean Foods") =
        Party(code = code, name = name)

    private fun line(id: Long = 5L) = ContractLine(
        lineId = id, contractNumber = "703", isGift = false, lineNo = 1,
        desiredQty = 10, spotCount = 3, placements = 2, totalSeconds = 60,
    )

    private fun spot(id: Long = 42L) =
        ContractLineSpot(spotId = id, description = "Spot $id", durationSeconds = 30, placements = 1)

    // ── search ──────────────────────────────────────────────────────────

    @Test
    fun searchWaitsForTheDebounceAndTheThirdCharacter() = runTest(testDispatcher) {
        partySearch.results = listOf(party())
        val vm = vm()

        vm.onAction(SpotFinderIntent.QueryChanged("ae"))
        advanceUntilIdle()
        assertTrue(partySearch.queries.isEmpty(), "two characters never hit the server")

        vm.onAction(SpotFinderIntent.QueryChanged("aeg"))
        advanceTimeBy(599)
        assertTrue(partySearch.queries.isEmpty(), "the 600ms debounce is still holding")

        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(listOf("aeg" to PartyKind.CUSTOMER), partySearch.queries)
        assertEquals(1, vm.state.results.size)
        assertFalse(vm.state.searching)
    }

    @Test
    fun typingAgainRestartsTheDebounce() = runTest(testDispatcher) {
        val vm = vm()

        vm.onAction(SpotFinderIntent.QueryChanged("aeg"))
        advanceTimeBy(300)
        vm.onAction(SpotFinderIntent.QueryChanged("aege"))
        advanceTimeBy(700)
        advanceUntilIdle()

        assertEquals(
            listOf("aege" to PartyKind.CUSTOMER),
            partySearch.queries,
            "only the LAST keystroke's query goes out - the earlier one was cancelled",
        )
    }

    @Test
    fun kindToggleSearchesUnderTheNewKind() = runTest(testDispatcher) {
        val vm = vm()
        vm.onAction(SpotFinderIntent.QueryChanged("aeg"))
        vm.onAction(SpotFinderIntent.KindChanged(PartyKind.TRADER))
        advanceTimeBy(601)
        advanceUntilIdle()

        assertEquals(listOf("aeg" to PartyKind.TRADER), partySearch.queries)
    }

    @Test
    fun searchFailureSurfacesOnceThroughTheGlobalSnackbar() = runTest(testDispatcher) {
        partySearch.failure = DataError.Network.NO_INTERNET
        val effects = mutableListOf<GlobalEffect>()
        backgroundScope.launch { globalContainer.effects.collect { effects += it } }
        val vm = vm()

        vm.onAction(SpotFinderIntent.QueryChanged("aeg"))
        advanceTimeBy(601)
        advanceUntilIdle()

        assertEquals(1, effects.count { it is GlobalEffect.SnackBarMessage }, "ONE error policy - the app snackbar")
        assertFalse(vm.state.searching, "the spinner never sticks on a failure")
    }

    // ── drill-down: the selection goes UP, the lists stay local ─────────

    @Test
    fun partySelectionDelegatesUpAndLoadsItsContractLines() = runTest(testDispatcher) {
        finder.lines = listOf(line())
        val vm = vm()

        vm.onAction(SpotFinderIntent.PartySelected(party()))
        advanceUntilIdle()

        assertEquals(listOf(party() to PartyKind.CUSTOMER), common.partySelections, "the selection is SHARED state")
        assertEquals(listOf("CUS1" to PartyKind.CUSTOMER), finder.lineQueries)
        assertEquals(1, vm.state.lines.size, "the lines table is the window's own")
        assertEquals(party(), vm.state.selection.party, "and the shared selection merged back down")
        assertEquals("", vm.state.query, "picking a party clears the search box, like the legacy console")
    }

    @Test
    fun lineSelectionPushesItsSpotsIntoTheSharedState() = runTest(testDispatcher) {
        finder.spots = listOf(spot(1), spot(2))
        val vm = vm()

        vm.onAction(SpotFinderIntent.LineSelected(line()))
        advanceUntilIdle()

        assertEquals(listOf(line()), common.lineSelections)
        assertEquals(listOf(5L), finder.spotQueries)
        // The spots are what the grid header's dropdown switches among - they
        // are SHARED, so they landed in common, and the window reads the merge.
        assertEquals(1, common.spotsSets.size)
        assertEquals(2, vm.state.selection.spots.size)
        assertNull(vm.state.selection.spot, "a new line disarms the previous spot - nothing auto-arms")
    }

    @Test
    fun spotSelectionJustDelegates() = runTest(testDispatcher) {
        val vm = vm()

        vm.onAction(SpotFinderIntent.SpotSelected(spot()))
        advanceUntilIdle()

        assertEquals(listOf<ContractLineSpot?>(spot()), common.spotSelections)
        assertEquals(spot(), vm.state.selection.spot, "armed - the grid's 'a' key reads this same state")
    }

    @Test
    fun clearResetsTheSearchAndDropsTheSharedSelection() = runTest(testDispatcher) {
        finder.lines = listOf(line())
        finder.spots = listOf(spot())
        val vm = vm()
        vm.onAction(SpotFinderIntent.PartySelected(party()))
        vm.onAction(SpotFinderIntent.LineSelected(line()))
        advanceUntilIdle()

        vm.onAction(SpotFinderIntent.Clear)
        advanceUntilIdle()

        assertEquals(1, common.finderClears)
        assertTrue(vm.state.lines.isEmpty())
        assertTrue(vm.state.results.isEmpty())
        assertNull(vm.state.selection.party)
        assertNull(vm.state.selection.spot)
    }

    // ── the window lifecycle seams ──────────────────────────────────────

    /**
     * Closing the window destroys the ViewModel but NOT the selection (it is
     * the flow's). A fresh window over that surviving selection re-fetches
     * the picked party's lines so the drill-down shows where it came from.
     */
    @Test
    fun freshWindowOverASurvivingSelectionRestoresTheDrillDown() = runTest(testDispatcher) {
        common.selectFinderParty(party(), PartyKind.TRADER)
        finder.lines = listOf(line())

        val vm = vm()
        advanceUntilIdle()

        assertEquals(listOf("CUS1" to PartyKind.TRADER), finder.lineQueries, "onStart re-fetches the lines")
        assertEquals(1, vm.state.lines.size)
        assertEquals(PartyKind.TRADER, vm.state.kind, "the radios start on the kind the selection was made under")
    }

    /** The header's X clears from OUTSIDE the window - its tables must follow. */
    @Test
    fun externalClearEmptiesTheLinesTable() = runTest(testDispatcher) {
        finder.lines = listOf(line())
        val vm = vm()
        vm.onAction(SpotFinderIntent.PartySelected(party()))
        advanceUntilIdle()
        assertEquals(1, vm.state.lines.size)

        common.clearFinder()   // the Μηνύματα box's X, not this window
        advanceUntilIdle()

        assertNull(vm.state.selection.party)
        assertTrue(vm.state.lines.isEmpty(), "a lines table for a no-longer-picked party would lie")
    }

    @Test
    fun drillDownFailureSurfacesOnceAndUnsticksTheBusyFlag() = runTest(testDispatcher) {
        finder.failure = DataError.Network.SERVER_ERROR
        val effects = mutableListOf<GlobalEffect>()
        backgroundScope.launch { globalContainer.effects.collect { effects += it } }
        val vm = vm()

        vm.onAction(SpotFinderIntent.PartySelected(party()))
        advanceUntilIdle()

        assertEquals(1, effects.count { it is GlobalEffect.SnackBarMessage })
        assertFalse(vm.state.loadingLines)
        // The selection still went up - the failure was the LINES fetch, not the pick.
        assertEquals(1, common.partySelections.size)
    }
}
