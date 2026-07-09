package eu.anifantakis.commercials.feature.migration_console.presentation.screens.migration

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.feature.migration_console.domain.BrowseListing
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationFlowChoice
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationRepository
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStart
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertNull

/**
 * The super-admin migration console VM: form reducers, the canStart /
 * canChooseFlow guards, and the steer verbs (start / chooseFlow / reset /
 * browse) mapping onto state. The live status poll is defused by a fake whose
 * [status] never returns - the poll coroutine parks harmlessly (viewModelScope,
 * not the test scope), leaving the intent-driven paths under test.
 */
class MigrationViewModelTest {

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

    private class FakeMigrationRepository(
        var startResult: DataResult<MigrationStatus, RemoteError> = DataResult.Success(MigrationStatus(state = "REPLAYING")),
        var chooseResult: DataResult<MigrationStatus, RemoteError> = DataResult.Success(MigrationStatus(state = "DONE")),
        var resetResult: DataResult<MigrationStatus, RemoteError> = DataResult.Success(MigrationStatus(state = "IDLE")),
        var browseResult: DataResult<BrowseListing, RemoteError> = DataResult.Success(BrowseListing(path = "/home")),
    ) : MigrationRepository {
        // Never completes: the polling loop's first status() call parks here, so
        // it never schedules a delay and never busy-loops the virtual clock.
        private val statusGate = CompletableDeferred<DataResult<MigrationStatus, RemoteError>>()

        var startCalls = 0
        var lastStart: MigrationStart? = null
        var lastChoose: MigrationFlowChoice? = null
        val browsePaths = mutableListOf<String?>()

        override suspend fun status(): DataResult<MigrationStatus, RemoteError> = statusGate.await()

        override suspend fun start(request: MigrationStart): DataResult<MigrationStatus, RemoteError> {
            startCalls++
            lastStart = request
            return startResult
        }

        override suspend fun chooseFlow(choice: MigrationFlowChoice): DataResult<MigrationStatus, RemoteError> {
            lastChoose = choice
            return chooseResult
        }

        override suspend fun reset(): DataResult<MigrationStatus, RemoteError> = resetResult

        override suspend fun browse(path: String?): DataResult<BrowseListing, RemoteError> {
            browsePaths += path
            return browseResult
        }
    }

    private fun fillSource(vm: MigrationViewModel) {
        vm.onAction(MigrationIntent.DumpPathChanged("/srv/dump.sql"))
        vm.onAction(MigrationIntent.UsernameChanged("root"))
        vm.onAction(MigrationIntent.SchemaChanged("legacy"))
    }

    @Test
    fun formFieldIntentsUpdateState() = runTest(testDispatcher) {
        val vm = MigrationViewModel(FakeMigrationRepository())

        vm.onAction(MigrationIntent.DumpPathChanged("/srv/dump.sql"))
        vm.onAction(MigrationIntent.SchemaChanged("legacy"))

        assertEquals("/srv/dump.sql", vm.state.dumpPath)
        assertEquals("legacy", vm.state.schema)
    }

    @Test
    fun portChangedKeepsOnlyDigits() = runTest(testDispatcher) {
        val vm = MigrationViewModel(FakeMigrationRepository())

        vm.onAction(MigrationIntent.PortChanged("3a3b06"))

        assertEquals("3306", vm.state.port, "the port field is digit-only")
    }

    @Test
    fun startWithIncompleteFormIsANoOp() = runTest(testDispatcher) {
        val repo = FakeMigrationRepository()
        val vm = MigrationViewModel(repo)

        vm.onAction(MigrationIntent.Start)   // dumpPath/username/schema blank
        advanceUntilIdle()

        assertEquals(0, repo.startCalls, "canStart gates the server call")
    }

    @Test
    fun startWithValidFormSendsTheTrimmedRequestAndAdoptsTheStatus() = runTest(testDispatcher) {
        val repo = FakeMigrationRepository()
        val vm = MigrationViewModel(repo)
        fillSource(vm)
        vm.onAction(MigrationIntent.PortChanged("3307"))

        vm.onAction(MigrationIntent.Start)
        advanceUntilIdle()

        assertEquals("legacy", repo.lastStart?.schema)
        assertEquals(3307, repo.lastStart?.port, "the digit string parses into the request port")
        assertNull(repo.lastStart?.senDirPath, "a blank SEN folder travels as null (enrichment skipped)")
        assertEquals("REPLAYING", vm.state.status.state, "the returned status replaces the local one")
    }

    @Test
    fun startCarriesTheSenFolderWhenGiven() = runTest(testDispatcher) {
        val repo = FakeMigrationRepository()
        val vm = MigrationViewModel(repo)
        fillSource(vm)
        vm.onAction(MigrationIntent.SenDirChanged("  /backups/SEN  "))

        vm.onAction(MigrationIntent.Start)
        advanceUntilIdle()

        assertEquals("/backups/SEN", repo.lastStart?.senDirPath, "trimmed SEN folder reaches the request")
    }

    @Test
    fun startFailureSurfacesTheServerMessageAsFormError() = runTest(testDispatcher) {
        val repo = FakeMigrationRepository(startResult = DataResult.Failure(RemoteError.Server("bad dump")))
        val vm = MigrationViewModel(repo)
        fillSource(vm)

        vm.onAction(MigrationIntent.Start)
        advanceUntilIdle()

        assertEquals(UiText.Dynamic("bad dump"), vm.state.formError)
    }

    @Test
    fun chooseFlowWithoutYamlNeedsNoStationAndAdoptsTheStatus() = runTest(testDispatcher) {
        val repo = FakeMigrationRepository()
        val vm = MigrationViewModel(repo)

        vm.onAction(MigrationIntent.FlowSelected(1))
        vm.onAction(MigrationIntent.AddToYamlChanged(false))   // no station id/name required
        vm.onAction(MigrationIntent.ChooseFlow)
        advanceUntilIdle()

        assertEquals(1, repo.lastChoose?.forTv)
        assertEquals("DONE", vm.state.status.state)
    }

    @Test
    fun resetClearsTheSelectedFlowAndAdoptsTheStatus() = runTest(testDispatcher) {
        val repo = FakeMigrationRepository()
        val vm = MigrationViewModel(repo)
        vm.onAction(MigrationIntent.FlowSelected(1))

        vm.onAction(MigrationIntent.Reset)
        advanceUntilIdle()

        assertNull(vm.state.selectedFlow)
        assertEquals("IDLE", vm.state.status.state)
    }

    @Test
    fun openBrowserListsRootThenPickingADumpClosesItAndSetsThePath() = runTest(testDispatcher) {
        val repo = FakeMigrationRepository()
        val vm = MigrationViewModel(repo)

        vm.onAction(MigrationIntent.OpenBrowser())
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), repo.browsePaths, "opening the picker browses the server home dir")
        assertEquals("/home", vm.state.browser?.listing?.path)
        assertEquals(false, vm.state.browser?.forSenDir, "default mode picks a dump file")

        vm.onAction(MigrationIntent.DumpPicked("/srv/x/dump.sql"))

        assertEquals("/srv/x/dump.sql", vm.state.dumpPath)
        assertNull(vm.state.browser, "picking a file closes the browser")
    }

    @Test
    fun senFolderModePicksTheCurrentFolderAndClosesTheBrowser() = runTest(testDispatcher) {
        val repo = FakeMigrationRepository()
        val vm = MigrationViewModel(repo)

        vm.onAction(MigrationIntent.OpenBrowser(forSenDir = true))
        advanceUntilIdle()

        assertEquals(true, vm.state.browser?.forSenDir, "the dialog opens in folder mode")

        vm.onAction(MigrationIntent.SenDirPicked("/backups/SEN"))

        assertEquals("/backups/SEN", vm.state.senDirPath)
        assertNull(vm.state.browser, "confirming the folder closes the browser")
    }
}
