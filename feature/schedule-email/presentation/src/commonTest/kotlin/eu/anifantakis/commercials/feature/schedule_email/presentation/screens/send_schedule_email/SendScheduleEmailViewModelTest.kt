package eu.anifantakis.commercials.feature.schedule_email.presentation.screens.send_schedule_email

import eu.anifantakis.commercials.feature.schedule_email.presentation.FakePartySearchRepository
import eu.anifantakis.commercials.feature.schedule_email.presentation.FakeScheduleEmailRepository
import eu.anifantakis.commercials.feature.schedule_email.presentation.ScheduleEmailTestBase
import eu.anifantakis.commercials.feature.schedule_email.presentation.party
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The send-dialog assembler VM: debounced party search, the party -> year ->
 * month drill-down, spot toggling, and the [EmailPreviewRequest] it emits.
 * Fakes over both repo contracts; no engine, no debounce wall-clock (virtual
 * time via the test scheduler).
 */
class SendScheduleEmailViewModelTest : ScheduleEmailTestBase() {

    private val email = FakeScheduleEmailRepository()
    private val search = FakePartySearchRepository()

    private fun vm() = SendScheduleEmailViewModel(email, search)

    /** Drives party -> month so the state is preview-ready. */
    private fun SendScheduleEmailViewModel.selectPartyAndMonth() {
        onAction(SendScheduleEmailIntent.PartySelected(party("CUS1")))
        onAction(SendScheduleEmailIntent.MonthSelected(2026, 7))
    }

    @Test
    fun shortQueryClearsResultsWithoutHittingTheSearchRepo() = runTest(testDispatcher) {
        val vm = vm()

        vm.onAction(SendScheduleEmailIntent.QueryChanged("ab"))   // < 3 chars
        advanceUntilIdle()

        assertTrue(vm.state.results.isEmpty())
        assertEquals(0, search.searchCalls, "under the 3-char floor there is no server call")
    }

    @Test
    fun queryRunsTheDebouncedSearchAndPublishesResults() = runTest(testDispatcher) {
        search.results = listOf(party("CUS1"), party("CUS2"))
        val vm = vm()

        vm.onAction(SendScheduleEmailIntent.QueryChanged("acme"))
        advanceUntilIdle()   // past the 600ms debounce

        assertEquals(1, search.searchCalls)
        assertEquals(listOf("CUS1", "CUS2"), vm.state.results.map { it.code })
        assertFalse(vm.state.searching)
    }

    @Test
    fun partySelectedLoadsActivityAndPrefillsRecipientFromTheEmail() = runTest(testDispatcher) {
        val vm = vm()

        vm.onAction(SendScheduleEmailIntent.PartySelected(party("CUS1", email = "c@example.gr")))
        advanceUntilIdle()

        assertEquals("c@example.gr", vm.state.recipient, "the party's email prefills the recipient")
        assertEquals(2026, vm.state.selectedYear, "the most recent activity year is auto-selected")
        assertFalse(vm.state.loadingActivity)
    }

    @Test
    fun monthSelectedLoadsSpotsAllIncludedByDefault() = runTest(testDispatcher) {
        val vm = vm()
        vm.selectPartyAndMonth()
        advanceUntilIdle()

        assertEquals(listOf(1L), vm.state.spots.map { it.spotId })
        assertEquals(setOf(1L), vm.state.includedSpotIds, "the month's spots start all-checked")
    }

    @Test
    fun spotToggledExcludesThenReincludes() = runTest(testDispatcher) {
        val vm = vm()
        vm.selectPartyAndMonth()
        advanceUntilIdle()

        vm.onAction(SendScheduleEmailIntent.SpotToggled(1L))
        assertFalse(1L in vm.state.includedSpotIds)

        vm.onAction(SendScheduleEmailIntent.SpotToggled(1L))
        assertTrue(1L in vm.state.includedSpotIds)
    }

    @Test
    fun requestPreviewEmitsOpenPreviewWithTheAssembledRequest() = runTest(testDispatcher) {
        val vm = vm()
        vm.selectPartyAndMonth()
        advanceUntilIdle()

        val effects = mutableListOf<SendScheduleEmailEffect>()
        val job = launch { vm.events.collect { effects += it } }

        vm.onAction(SendScheduleEmailIntent.RequestPreview)
        advanceUntilIdle()

        assertEquals(1, effects.size)
        val request = (effects.first() as SendScheduleEmailEffect.OpenPreview).request
        assertEquals(2026, request.year)
        assertEquals(7, request.month)
        assertEquals("CUS1", request.clientCode)
        assertEquals(listOf(1L), request.spotIds, "only the checked spots go into the request")
        job.cancel()
    }

    @Test
    fun requestPreviewIsANoOpWhileTheFormIsIncomplete() = runTest(testDispatcher) {
        val vm = vm()   // nothing selected -> canPreview false

        val effects = mutableListOf<SendScheduleEmailEffect>()
        val job = launch { vm.events.collect { effects += it } }

        vm.onAction(SendScheduleEmailIntent.RequestPreview)
        advanceUntilIdle()

        assertTrue(effects.isEmpty(), "no preview is opened until the request is complete")
        job.cancel()
    }
}
