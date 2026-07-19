package eu.anifantakis.commercials.feature.galaxy_bridge.presentation.screens.galaxy_bridge

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyBridgeRepository
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyDelivery
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyGroup
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyReview
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyStart
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyStatus
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyUploadKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Galaxy Bridge VM: selection reducers, the canRun guard, the APPLY
 * confirmation gate (a write to a live group database never fires off one
 * click), uploads, and the harmless auto-fills. The live status poll is
 * defused by a fake whose [status] never returns - the poll coroutine parks
 * harmlessly, leaving the intent-driven paths under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalaxyBridgeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        startKoin { modules(module { single { GlobalStateContainer() } }) }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    private val readyStatus = GalaxyStatus(
        state = "IDLE",
        groups = listOf(GalaxyGroup("crete-group", "Crete Group", "commercials_crete_group")),
        deliveries = listOf(
            GalaxyDelivery("galaxy-2026-07", files = 7, uploadedAtMillis = 2),
            GalaxyDelivery("galaxy-2026-06", files = 7, uploadedAtMillis = 1),
        ),
        dictionaryPresent = true,
    )

    private class FakeRepository(
        var startResult: DataResult<GalaxyStatus, RemoteError> = DataResult.Success(GalaxyStatus(state = "RUNNING")),
        var resetResult: DataResult<GalaxyStatus, RemoteError> = DataResult.Success(GalaxyStatus(state = "IDLE")),
        var uploadResult: DataResult<GalaxyStatus, RemoteError> = DataResult.Success(GalaxyStatus(state = "IDLE")),
    ) : GalaxyBridgeRepository {
        // Never completes: the polling loop's first status() call parks here.
        private val statusGate = CompletableDeferred<DataResult<GalaxyStatus, RemoteError>>()

        var startCalls = 0
        var lastStart: GalaxyStart? = null
        var lastUploadKind: GalaxyUploadKind? = null
        var lastUploadName: String? = null

        override suspend fun status(): DataResult<GalaxyStatus, RemoteError> = statusGate.await()

        override suspend fun start(request: GalaxyStart): DataResult<GalaxyStatus, RemoteError> {
            startCalls++
            lastStart = request
            return startResult
        }

        override suspend fun reset(): DataResult<GalaxyStatus, RemoteError> = resetResult

        override suspend fun upload(
            kind: GalaxyUploadKind,
            name: String,
            fileName: String,
            bytes: ByteArray,
        ): DataResult<GalaxyStatus, RemoteError> {
            lastUploadKind = kind
            lastUploadName = name
            return uploadResult
        }
    }

    private fun viewModel(repository: GalaxyBridgeRepository = FakeRepository()) =
        GalaxyBridgeViewModel(repository)

    /** A ready-to-run selection: group, company default, delivery. */
    private fun fillSelections(vm: GalaxyBridgeViewModel) {
        vm.onAction(GalaxyBridgeIntent.GroupSelected("crete-group"))
        vm.onAction(GalaxyBridgeIntent.DeliverySelected("galaxy-2026-07"))
    }

    @Test
    fun selectionIntentsUpdateState() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.onAction(GalaxyBridgeIntent.GroupSelected("crete-group"))
        vm.onAction(GalaxyBridgeIntent.CompanySelected("003"))
        vm.onAction(GalaxyBridgeIntent.DeliverySelected("galaxy-2026-07"))

        assertEquals("crete-group", vm.state.selectedGroupId)
        assertEquals("003", vm.state.selectedCompany)
        assertEquals("galaxy-2026-07", vm.state.selectedDelivery)
    }

    @Test
    fun dryRunWithoutSelectionsIsANoOp() = runTest(testDispatcher) {
        val repo = FakeRepository()
        val vm = viewModel(repo)

        vm.onAction(GalaxyBridgeIntent.RunDryRun)   // no group, no delivery
        advanceUntilIdle()

        assertEquals(0, repo.startCalls, "canRun gates the server call")
    }

    @Test
    fun dryRunSendsApplyFalseAndAdoptsTheStatus() = runTest(testDispatcher) {
        val repo = FakeRepository()
        val vm = viewModel(repo)
        fillSelections(vm)

        vm.onAction(GalaxyBridgeIntent.RunDryRun)
        advanceUntilIdle()

        assertEquals(false, repo.lastStart?.apply, "the dry-run button NEVER writes")
        assertEquals("001", repo.lastStart?.companyCode, "company defaults to 001 (crete)")
        assertEquals("RUNNING", vm.state.status.state, "the returned status replaces the local one")
    }

    /**
     * THE GATE THAT KEEPS APPLY HONEST: a click on Apply only OPENS the
     * confirmation; nothing reaches the server until ConfirmApply.
     */
    @Test
    fun applyAsksForConfirmationBeforeTouchingTheServer() = runTest(testDispatcher) {
        val repo = FakeRepository()
        val vm = viewModel(repo)
        fillSelections(vm)

        vm.onAction(GalaxyBridgeIntent.AskApply)
        advanceUntilIdle()

        assertTrue(vm.state.confirmApply, "the dialog is open")
        assertEquals(0, repo.startCalls, "no server call before confirmation")

        vm.onAction(GalaxyBridgeIntent.ConfirmApply)
        advanceUntilIdle()

        assertFalse(vm.state.confirmApply)
        assertEquals(true, repo.lastStart?.apply)
    }

    @Test
    fun dismissingTheApplyDialogRunsNothing() = runTest(testDispatcher) {
        val repo = FakeRepository()
        val vm = viewModel(repo)
        fillSelections(vm)

        vm.onAction(GalaxyBridgeIntent.AskApply)
        vm.onAction(GalaxyBridgeIntent.DismissApply)
        advanceUntilIdle()

        assertFalse(vm.state.confirmApply)
        assertEquals(0, repo.startCalls)
    }

    @Test
    fun startFailureSurfacesTheServerMessageAsFormError() = runTest(testDispatcher) {
        val repo = FakeRepository(startResult = DataResult.Failure(RemoteError.Server("unknown group")))
        val vm = viewModel(repo)
        fillSelections(vm)

        vm.onAction(GalaxyBridgeIntent.RunDryRun)
        advanceUntilIdle()

        assertEquals(UiText.Dynamic("unknown group"), vm.state.formError)
    }

    @Test
    fun aSuccessfulDeliveryUploadSelectsIt() = runTest(testDispatcher) {
        val repo = FakeRepository(uploadResult = DataResult.Success(readyStatus))
        val vm = viewModel(repo)

        vm.onAction(GalaxyBridgeIntent.UploadDelivery("galaxy-2026-07", "galaxy-2026-07.zip", ByteArray(4)))
        advanceUntilIdle()

        assertEquals(GalaxyUploadKind.DELIVERY, repo.lastUploadKind)
        assertEquals("galaxy-2026-07", repo.lastUploadName)
        assertEquals("galaxy-2026-07", vm.state.selectedDelivery, "the fresh upload becomes the selection")
        assertFalse(vm.state.uploadingDelivery, "the busy flag clears")
    }

    @Test
    fun uploadFailureSurfacesTheErrorAndClearsTheBusyFlag() = runTest(testDispatcher) {
        val repo = FakeRepository(uploadResult = DataResult.Failure(RemoteError.Server("bad zip")))
        val vm = viewModel(repo)

        vm.onAction(GalaxyBridgeIntent.UploadDictionary("customer.zip", ByteArray(4)))
        advanceUntilIdle()

        assertEquals(GalaxyUploadKind.DICTIONARY, repo.lastUploadKind)
        assertEquals(UiText.Dynamic("bad zip"), vm.state.formError)
        assertFalse(vm.state.uploadingDictionary)
    }

    /**
     * Harmless auto-fills only: the NEWEST delivery when none is picked, and
     * the group only when there is exactly ONE hosted - a database target is
     * never guessed among many.
     */
    @Test
    fun aFreshStatusAutoFillsDeliveryAndSingleGroup() = runTest(testDispatcher) {
        val repo = FakeRepository(resetResult = DataResult.Success(readyStatus))
        val vm = viewModel(repo)

        vm.onAction(GalaxyBridgeIntent.Reset)
        advanceUntilIdle()

        assertEquals("galaxy-2026-07", vm.state.selectedDelivery, "newest delivery auto-selected")
        assertEquals("crete-group", vm.state.selectedGroupId, "a SINGLE hosted group is safe to preselect")
    }

    @Test
    fun aVanishedDeliverySelectionFallsBackToTheNewest() = runTest(testDispatcher) {
        val repo = FakeRepository(resetResult = DataResult.Success(readyStatus))
        val vm = viewModel(repo)
        vm.onAction(GalaxyBridgeIntent.DeliverySelected("deleted-one"))

        vm.onAction(GalaxyBridgeIntent.Reset)
        advanceUntilIdle()

        assertEquals("galaxy-2026-07", vm.state.selectedDelivery, "a stale selection never reaches /start")
    }

    @Test
    fun reviewFilterNarrowsTheListAndCountsPerKind() = runTest(testDispatcher) {
        val reviews = listOf(
            GalaxyReview("doc-ambiguous", "001:9001:1", "a"),
            GalaxyReview("doc-ambiguous", "001:9001:2", "b"),
            GalaxyReview("party-multi-claim", "30000016", "c"),
        )
        val repo = FakeRepository(resetResult = DataResult.Success(GalaxyStatus(state = "DONE", reviews = reviews)))
        val vm = viewModel(repo)

        vm.onAction(GalaxyBridgeIntent.Reset)
        advanceUntilIdle()

        assertEquals(listOf("doc-ambiguous" to 2, "party-multi-claim" to 1), vm.state.reviewKinds)
        assertEquals(3, vm.state.filteredReviews.size)

        vm.onAction(GalaxyBridgeIntent.ReviewFilterChanged("party-multi-claim"))
        assertEquals(listOf("30000016"), vm.state.filteredReviews.map { it.key })

        vm.onAction(GalaxyBridgeIntent.ReviewFilterChanged(null))
        assertEquals(3, vm.state.filteredReviews.size)
    }

    @Test
    fun resetAdoptsTheIdleStatus() = runTest(testDispatcher) {
        val repo = FakeRepository()
        val vm = viewModel(repo)

        vm.onAction(GalaxyBridgeIntent.Reset)
        advanceUntilIdle()

        assertEquals("IDLE", vm.state.status.state)
        assertNull(vm.state.formError)
    }
}
