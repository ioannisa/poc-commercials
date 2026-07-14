package eu.anifantakis.commercials.feature.databases.presentation.screens.databases

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.feature.databases.domain.DatabasesRepository
import eu.anifantakis.commercials.feature.databases.domain.DeleteMode
import eu.anifantakis.commercials.feature.databases.domain.HostedStation
import eu.anifantakis.commercials.feature.databases.domain.StationDeletion
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The super-admin Databases screen VM: lists hosted stations on start and
 * gates the destructive drop behind a typed-id confirmation. A fake repo over
 * the domain contract proves the guard fires and the outcome maps to state.
 */
class DatabasesViewModelTest {

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

    // A station that SHARES its group's database with a sibling - the normal case
    // now, and what makes "drop the group" a different decision from "delete the
    // station".
    private val station = HostedStation(
        id = "crete-tv",
        name = "Crete TV",
        database = "commercials_crete",
        groupId = "crete-group",
        groupName = "Crete Group",
        siblings = listOf("radio-984"),
    )

    private class FakeDatabasesRepository(
        var stations: List<HostedStation> = emptyList(),
        var listResult: DataResult<List<HostedStation>, RemoteError>? = null,
        var deleteResult: DataResult<StationDeletion, RemoteError> = DataResult.Success(
            StationDeletion(status = "ok", grantsRemoved = 3, yamlEntryRemoved = true, databaseDropped = false),
        ),
    ) : DatabasesRepository {
        var deleteCalls = 0
        var lastDelete: Triple<String, DeleteMode, String>? = null

        override suspend fun listStations(): DataResult<List<HostedStation>, RemoteError> =
            listResult ?: DataResult.Success(stations)

        override suspend fun deleteStation(
            id: String,
            mode: DeleteMode,
            confirmId: String,
        ): DataResult<StationDeletion, RemoteError> {
            deleteCalls++
            lastDelete = Triple(id, mode, confirmId)
            return deleteResult
        }
    }

    @Test
    fun reloadOnStartPopulatesStations() = runTest(testDispatcher) {
        val vm = DatabasesViewModel(FakeDatabasesRepository(stations = listOf(station)))
        advanceUntilIdle()

        assertEquals(listOf("crete-tv"), vm.state.stations.map { it.id }, "the list loads as soon as the screen subscribes")
    }

    @Test
    fun reloadFailureSurfacesTheServerMessageVerbatim() = runTest(testDispatcher) {
        val vm = DatabasesViewModel(FakeDatabasesRepository(listResult = DataResult.Failure(RemoteError.Server("nope"))))
        advanceUntilIdle()

        assertEquals(UiText.Dynamic("nope"), vm.state.error)
    }

    @Test
    fun deleteRequestedOpensTheTypedConfirmDialog() = runTest(testDispatcher) {
        val vm = DatabasesViewModel(FakeDatabasesRepository())

        vm.onAction(DatabasesIntent.DeleteRequested(station))

        assertEquals(station, vm.state.delete?.station)
        assertFalse(vm.state.delete?.canConfirm ?: true, "the id must be retyped before confirm unlocks")
    }

    @Test
    fun confirmDeleteIsBlockedUntilTheIdIsRetyped() = runTest(testDispatcher) {
        val repo = FakeDatabasesRepository()
        val vm = DatabasesViewModel(repo)

        vm.onAction(DatabasesIntent.DeleteRequested(station))
        vm.onAction(DatabasesIntent.ConfirmDelete)   // confirmId still blank
        advanceUntilIdle()

        assertEquals(0, repo.deleteCalls, "the typed-confirmation guard blocks the destructive call")
    }

    @Test
    fun confirmDeleteWithMatchingIdDropsReportsAndReloads() = runTest(testDispatcher) {
        val repo = FakeDatabasesRepository(stations = listOf(station))
        val vm = DatabasesViewModel(repo)
        advanceUntilIdle()

        vm.onAction(DatabasesIntent.DeleteRequested(station))
        vm.onAction(DatabasesIntent.DeleteModeChanged(DeleteMode.PURGE))
        vm.onAction(DatabasesIntent.ConfirmIdChanged("crete-tv"))
        vm.onAction(DatabasesIntent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(Triple("crete-tv", DeleteMode.PURGE, "crete-tv"), repo.lastDelete, "purge mode + typed id reach the repo")
        assertEquals(null, vm.state.delete, "the dialog closes on success")
        assertEquals(
            UiText.Res(StringKey.DATABASES_DELETED_STATUS, listOf<Any>("ok", 3)),
            vm.state.message,
            "the server's status + grants-removed count feed the localized result line",
        )
    }

    @Test
    fun confirmDeleteFailureKeepsTheDialogOpenWithError() = runTest(testDispatcher) {
        val vm = DatabasesViewModel(FakeDatabasesRepository(deleteResult = DataResult.Failure(RemoteError.Server("busy"))))

        vm.onAction(DatabasesIntent.DeleteRequested(station))
        vm.onAction(DatabasesIntent.ConfirmIdChanged("crete-tv"))
        vm.onAction(DatabasesIntent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(UiText.Dynamic("busy"), vm.state.delete?.error)
        assertFalse(vm.state.delete?.busy ?: true, "the button re-enables so the admin can retry")
    }

    /**
     * Dropping the group destroys the sibling stations too, so it is confirmed
     * with the GROUP's id. Typing the station's - the word muscle memory
     * supplies - must NOT unlock it.
     */
    @Test
    fun droppingTheGroupIsConfirmedWithTheGroupIdNotTheStationId() = runTest(testDispatcher) {
        val repo = FakeDatabasesRepository(stations = listOf(station))
        val vm = DatabasesViewModel(repo)

        vm.onAction(DatabasesIntent.DeleteRequested(station))
        vm.onAction(DatabasesIntent.DeleteModeChanged(DeleteMode.DROP_GROUP))
        vm.onAction(DatabasesIntent.ConfirmIdChanged("crete-tv"))   // the STATION id
        vm.onAction(DatabasesIntent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(0, repo.deleteCalls, "the station's own id does not unlock a group drop")

        vm.onAction(DatabasesIntent.ConfirmIdChanged("crete-group"))
        vm.onAction(DatabasesIntent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(Triple("crete-tv", DeleteMode.DROP_GROUP, "crete-group"), repo.lastDelete)
    }

    /** Switching mode re-arms the confirmation: it means a different word now. */
    @Test
    fun changingTheModeClearsTheTypedConfirmation() = runTest(testDispatcher) {
        val vm = DatabasesViewModel(FakeDatabasesRepository())

        vm.onAction(DatabasesIntent.DeleteRequested(station))
        vm.onAction(DatabasesIntent.ConfirmIdChanged("crete-tv"))
        assertTrue(vm.state.delete?.canConfirm ?: false, "safe mode is armed")

        vm.onAction(DatabasesIntent.DeleteModeChanged(DeleteMode.DROP_GROUP))

        assertEquals("", vm.state.delete?.confirmId)
        assertFalse(vm.state.delete?.canConfirm ?: true, "the admin must read the new dialog and retype")
    }
}
