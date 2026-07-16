package eu.anifantakis.commercials.feature.auth.presentation.screens.login

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.feature.auth.domain.AuthError
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.domain.model.ApiToken
import eu.anifantakis.commercials.feature.auth.domain.model.CreatedApiToken
import eu.anifantakis.commercials.feature.auth.domain.model.WorkstationAvailability
import eu.anifantakis.commercials.feature.auth.domain.model.ResetOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@OptIn(ExperimentalCoroutinesApi::class)
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
        var forgotResult: EmptyDataResult<AuthError> = DataResult.Success(Unit),
        var resetResult: DataResult<ResetOutcome, AuthError> = DataResult.Success(ResetOutcome.Success),
    ) : AuthRepository {
        var loginCalls = 0
        var forgotCalls = 0
        var resetCalls = 0

        override suspend fun login(username: String, password: String): EmptyDataResult<AuthError> {
            loginCalls++
            return loginResult
        }

        override suspend fun logout() = Unit

        override suspend fun changePassword(currentPassword: String, newPassword: String): EmptyDataResult<AuthError> =
            DataResult.Success(Unit)

        override suspend fun forgotPassword(username: String): EmptyDataResult<AuthError> {
            forgotCalls++
            return forgotResult
        }

        override suspend fun resetPassword(
            username: String,
            code: String,
            newPassword: String,
        ): DataResult<ResetOutcome, AuthError> {
            resetCalls++
            return resetResult
        }

        override suspend fun listApiTokens(): DataResult<List<ApiToken>, AuthError> = DataResult.Success(emptyList())
        override suspend fun checkWorkstation(workstation: String): DataResult<WorkstationAvailability, AuthError> =
            DataResult.Success(WorkstationAvailability.FREE)
        override suspend fun createApiToken(workstation: String, confirmTakeover: Boolean): DataResult<CreatedApiToken, AuthError> =
            DataResult.Success(CreatedApiToken("tok", "http://localhost/mcp"))
        override suspend fun revokeApiToken(id: Long): EmptyDataResult<AuthError> = DataResult.Success(Unit)
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
    fun startForgotArmsRequestModeAndClearsPassword() = runTest(testDispatcher) {
        val vm = LoginViewModel(FakeAuthRepository())
        vm.onAction(LoginIntent.PasswordChanged("secret"))

        vm.onAction(LoginIntent.StartForgot)

        assertEquals(LoginMode.FORGOT_REQUEST, vm.state.mode)
        assertEquals("", vm.state.password, "switching to forgot wipes the half-typed password")
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
    fun forgotFlowRequestsCodeThenResetsAndReturnsToLogin() = runTest(testDispatcher) {
        val repo = FakeAuthRepository(resetResult = DataResult.Success(ResetOutcome.Success))
        val vm = LoginViewModel(repo)
        vm.onAction(LoginIntent.StartForgot)
        vm.onAction(LoginIntent.UsernameChanged("admin"))

        // Step 1: request the emailed code.
        vm.onAction(LoginIntent.Submit)
        advanceUntilIdle()
        assertEquals(1, repo.forgotCalls)
        assertEquals(LoginMode.FORGOT_ENTER, vm.state.mode)
        assertEquals(UiText.Res(StringKey.LOGIN_CODE_SENT), vm.state.infoMessage)

        // Step 2: enter the 6-digit code + a new password, reset.
        vm.onAction(LoginIntent.CodeChanged("123456"))
        vm.onAction(LoginIntent.NewPasswordChanged("newpass"))
        vm.onAction(LoginIntent.Submit)
        advanceUntilIdle()

        assertEquals(1, repo.resetCalls)
        assertEquals(LoginMode.LOGIN, vm.state.mode, "success drops back to the normal login form")
        assertEquals(UiText.Res(StringKey.LOGIN_RESET_DONE), vm.state.infoMessage)
    }

    @Test
    fun wrongCodeWithLockArmsCountdownAndBlocksSubmit() = runTest(testDispatcher) {
        val repo = FakeAuthRepository(resetResult = DataResult.Success(ResetOutcome.Invalid(retryAfterSeconds = 10)))
        val vm = LoginViewModel(repo)
        vm.onAction(LoginIntent.StartForgot)
        vm.onAction(LoginIntent.UsernameChanged("admin"))
        vm.onAction(LoginIntent.Submit)
        advanceUntilIdle()

        vm.onAction(LoginIntent.CodeChanged("123456"))
        vm.onAction(LoginIntent.NewPasswordChanged("newpass"))
        // No advanceUntilIdle: the countdown would tick down to zero. Assert the
        // state right after the wrong code lands.
        vm.onAction(LoginIntent.Submit)

        assertEquals(UiText.Res(StringKey.LOGIN_RESET_INVALID), vm.state.errorMessage)
        assertTrue(vm.state.lockSeconds > 0, "a lock arms the countdown")
        assertFalse(vm.state.canSubmit, "submit is blocked while the lock counts down")
    }
}
