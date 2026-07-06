package eu.anifantakis.commercials.feature.auth.presentation.screens.login

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.feature.auth.domain.AuthError
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * The login screen's ViewModel: pure field reducers, the canSubmit guard, and
 * the two async paths (login / recovery) mapping the [AuthRepository] outcome
 * onto state and the one-shot [LoginEffect.LoggedIn]. A fake repo over the
 * domain contract - no engine, no session.
 */
class LoginViewModelTest {

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

    private class FakeAuthRepository(
        var loginResult: EmptyDataResult<AuthError> = DataResult.Success(Unit),
        var recoverResult: EmptyDataResult<AuthError> = DataResult.Success(Unit),
    ) : AuthRepository {
        var loginCalls = 0
        var recoverCalls = 0

        override suspend fun login(username: String, password: String): EmptyDataResult<AuthError> {
            loginCalls++
            return loginResult
        }

        override suspend fun logout() = Unit

        override suspend fun changePassword(currentPassword: String, newPassword: String): EmptyDataResult<AuthError> =
            DataResult.Success(Unit)

        override suspend fun recoverPassword(
            username: String,
            recoveryCode: String,
            newPassword: String,
        ): EmptyDataResult<AuthError> {
            recoverCalls++
            return recoverResult
        }

        override suspend fun regenerateRecoveryCodes(): DataResult<List<String>, AuthError> =
            DataResult.Success(emptyList())
    }

    private fun filledLogin(vm: LoginViewModel) {
        vm.onAction(LoginIntent.UsernameChanged("admin"))
        vm.onAction(LoginIntent.PasswordChanged("admin123"))
    }

    @Test
    fun fieldIntentsUpdateStateAndGateCanSubmit() = runTest(testDispatcher) {
        val vm = LoginViewModel(FakeAuthRepository())
        assertFalse(vm.state.canSubmit, "blank form can't submit")

        vm.onAction(LoginIntent.UsernameChanged("admin"))
        assertFalse(vm.state.canSubmit, "username alone isn't enough")
        vm.onAction(LoginIntent.PasswordChanged("pw"))

        assertEquals("admin", vm.state.username)
        assertTrue(vm.state.canSubmit, "username + password unlocks submit")
    }

    @Test
    fun togglePasswordVisibilityFlips() = runTest(testDispatcher) {
        val vm = LoginViewModel(FakeAuthRepository())
        assertFalse(vm.state.passwordVisible)

        vm.onAction(LoginIntent.TogglePasswordVisibility)

        assertTrue(vm.state.passwordVisible)
    }

    @Test
    fun toggleRecoveryModeArmsRecoveryAndClearsPasswordAndMessages() = runTest(testDispatcher) {
        val vm = LoginViewModel(FakeAuthRepository())
        vm.onAction(LoginIntent.PasswordChanged("secret"))

        vm.onAction(LoginIntent.ToggleRecoveryMode)

        assertTrue(vm.state.recoveryMode)
        assertEquals("", vm.state.password, "switching modes wipes the half-typed password")
    }

    @Test
    fun submitWithBlankFieldsIsANoOp() = runTest(testDispatcher) {
        val repo = FakeAuthRepository()
        val vm = LoginViewModel(repo)

        vm.onAction(LoginIntent.Submit)
        advanceUntilIdle()

        assertEquals(0, repo.loginCalls, "no network call until the form is fillable")
    }

    @Test
    fun successfulLoginEmitsLoggedInAndStopsLoading() = runTest(testDispatcher) {
        val vm = LoginViewModel(FakeAuthRepository(loginResult = DataResult.Success(Unit)))
        filledLogin(vm)

        val effects = mutableListOf<LoginEffect>()
        val job = launch { vm.events.collect { effects += it } }

        vm.onAction(LoginIntent.Submit)
        advanceUntilIdle()

        assertEquals(listOf<LoginEffect>(LoginEffect.LoggedIn), effects)
        assertFalse(vm.state.isLoading)
        job.cancel()
    }

    @Test
    fun failedLoginShowsTheMappedErrorAndClearsLoading() = runTest(testDispatcher) {
        val vm = LoginViewModel(FakeAuthRepository(loginResult = DataResult.Failure(AuthError.InvalidCredentials)))
        filledLogin(vm)

        vm.onAction(LoginIntent.Submit)
        advanceUntilIdle()

        assertEquals(UiText.Res(StringKey.AUTH_INVALID_CREDENTIALS), vm.state.errorMessage)
        assertFalse(vm.state.isLoading)
    }

    @Test
    fun recoverySubmitSuccessShowsInfoAndLeavesRecoveryMode() = runTest(testDispatcher) {
        val repo = FakeAuthRepository(recoverResult = DataResult.Success(Unit))
        val vm = LoginViewModel(repo)
        vm.onAction(LoginIntent.ToggleRecoveryMode)
        vm.onAction(LoginIntent.UsernameChanged("admin"))
        vm.onAction(LoginIntent.RecoveryCodeChanged("ABCD-1234"))
        vm.onAction(LoginIntent.PasswordChanged("newpass"))

        vm.onAction(LoginIntent.Submit)
        advanceUntilIdle()

        assertEquals(1, repo.recoverCalls, "recovery mode routes Submit to recoverPassword")
        assertEquals(UiText.Res(StringKey.LOGIN_RESET_DONE), vm.state.infoMessage)
        assertFalse(vm.state.recoveryMode, "success drops back to the normal login form")
    }
}
