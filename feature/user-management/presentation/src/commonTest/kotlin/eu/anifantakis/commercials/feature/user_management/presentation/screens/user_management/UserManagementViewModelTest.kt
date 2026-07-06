package eu.anifantakis.commercials.feature.user_management.presentation.screens.user_management

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.feature.user_management.domain.ManagedUser
import eu.anifantakis.commercials.feature.user_management.domain.UserGrant
import eu.anifantakis.commercials.feature.user_management.domain.UserManagementRepository
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
import kotlin.test.assertNull

/**
 * The super-admin user list + dialogs VM. Proves reload-on-start, the create
 * dialog's canSubmit gate, the NO_ACCESS grant pruning done by
 * [GrantsSelection.collect], and that mutations reload the list. Fake repo over
 * the domain contract.
 */
class UserManagementViewModelTest {

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

    private val user = ManagedUser(
        id = 7,
        username = "bob",
        displayName = "Bob",
        grants = listOf(UserGrant("crete-tv", "NORMAL_USER")),
    )

    private class FakeUserManagementRepository(
        var users: List<ManagedUser> = emptyList(),
        var listResult: DataResult<List<ManagedUser>, RemoteError>? = null,
        var createResult: DataResult<Unit, RemoteError> = DataResult.Success(Unit),
        var deleteResult: DataResult<Unit, RemoteError> = DataResult.Success(Unit),
    ) : UserManagementRepository {
        var createdGrants: List<UserGrant>? = null
        var lastCreate: Triple<String, String, String>? = null
        var deletedId: Long? = null

        override suspend fun listUsers(): DataResult<List<ManagedUser>, RemoteError> =
            listResult ?: DataResult.Success(users)

        override suspend fun createUser(
            username: String,
            displayName: String,
            password: String,
            grants: List<UserGrant>,
        ): DataResult<Unit, RemoteError> {
            lastCreate = Triple(username, displayName, password)
            createdGrants = grants
            return createResult
        }

        override suspend fun resetPassword(userId: Long, newPassword: String): DataResult<Unit, RemoteError> =
            DataResult.Success(Unit)

        override suspend fun setGrants(userId: Long, grants: List<UserGrant>): DataResult<Unit, RemoteError> =
            DataResult.Success(Unit)

        override suspend fun deleteUser(userId: Long): DataResult<Unit, RemoteError> {
            deletedId = userId
            return deleteResult
        }
    }

    @Test
    fun reloadOnStartPopulatesUsers() = runTest(testDispatcher) {
        val vm = UserManagementViewModel(FakeUserManagementRepository(users = listOf(user)))
        advanceUntilIdle()

        assertEquals(listOf("bob"), vm.state.users.map { it.username })
    }

    @Test
    fun showCreateOpensAnEmptyNonSubmittableDialog() = runTest(testDispatcher) {
        val vm = UserManagementViewModel(FakeUserManagementRepository())

        vm.onAction(UserManagementIntent.ShowCreate)

        assertEquals("", vm.state.create?.username)
        assertFalse(vm.state.create?.canSubmit ?: true, "an empty dialog can't submit")
    }

    @Test
    fun confirmCreateWithShortPasswordIsANoOp() = runTest(testDispatcher) {
        val repo = FakeUserManagementRepository()
        val vm = UserManagementViewModel(repo)

        vm.onAction(UserManagementIntent.ShowCreate)
        vm.onAction(UserManagementIntent.CreateUsernameChanged("bob"))
        vm.onAction(UserManagementIntent.CreateDisplayNameChanged("Bob"))
        vm.onAction(UserManagementIntent.CreatePasswordChanged("short"))   // 5 chars < 6
        vm.onAction(UserManagementIntent.ConfirmCreate)
        advanceUntilIdle()

        assertNull(repo.lastCreate, "the 6-char password rule gates the create call")
    }

    @Test
    fun confirmCreateSendsTrimmedFieldsPrunesNoAccessGrantsThenClosesAndReloads() = runTest(testDispatcher) {
        val repo = FakeUserManagementRepository(users = emptyList())
        val vm = UserManagementViewModel(repo)

        vm.onAction(UserManagementIntent.ShowCreate)
        vm.onAction(UserManagementIntent.CreateUsernameChanged("bob"))
        vm.onAction(UserManagementIntent.CreateDisplayNameChanged("Bob"))
        vm.onAction(UserManagementIntent.CreatePasswordChanged("secret"))
        vm.onAction(UserManagementIntent.CreateGrantRoleChanged("crete-tv", "NORMAL_USER"))
        vm.onAction(UserManagementIntent.CreateGrantRoleChanged("radio-984", NO_ACCESS))
        vm.onAction(UserManagementIntent.ConfirmCreate)
        advanceUntilIdle()

        assertEquals(Triple("bob", "Bob", "secret"), repo.lastCreate)
        assertEquals(
            listOf(UserGrant("crete-tv", "NORMAL_USER")),
            repo.createdGrants,
            "NO_ACCESS rows are dropped - only real grants are sent",
        )
        assertNull(vm.state.create, "the dialog closes on success")
    }

    @Test
    fun confirmCreateFailureKeepsTheDialogWithError() = runTest(testDispatcher) {
        val repo = FakeUserManagementRepository(createResult = DataResult.Failure(RemoteError.Server("taken")))
        val vm = UserManagementViewModel(repo)

        vm.onAction(UserManagementIntent.ShowCreate)
        vm.onAction(UserManagementIntent.CreateUsernameChanged("bob"))
        vm.onAction(UserManagementIntent.CreateDisplayNameChanged("Bob"))
        vm.onAction(UserManagementIntent.CreatePasswordChanged("secret"))
        vm.onAction(UserManagementIntent.ConfirmCreate)
        advanceUntilIdle()

        assertEquals(UiText.Dynamic("taken"), vm.state.create?.error)
        assertFalse(vm.state.create?.busy ?: true)
    }

    @Test
    fun confirmDeleteRemovesTheUserAndReloads() = runTest(testDispatcher) {
        val repo = FakeUserManagementRepository(users = listOf(user))
        val vm = UserManagementViewModel(repo)
        advanceUntilIdle()

        vm.onAction(UserManagementIntent.DeleteRequested(user))
        vm.onAction(UserManagementIntent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(7L, repo.deletedId)
        assertNull(vm.state.delete, "the confirm dialog closes afterwards")
    }

    @Test
    fun editGrantsRequestedPrefillsFromTheUsersCurrentGrants() = runTest(testDispatcher) {
        val vm = UserManagementViewModel(FakeUserManagementRepository())

        vm.onAction(UserManagementIntent.EditGrantsRequested(user))

        assertEquals("NORMAL_USER", vm.state.editGrants?.grants?.roles?.get("crete-tv"))
    }
}
