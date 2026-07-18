package eu.anifantakis.commercials.feature.auth.presentation.screens.login

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.domain.auth.BiometricAuth
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.domain.model.ResetOutcome
import eu.anifantakis.commercials.feature.auth.presentation.toUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Sign-in, or the two-step "forgot password": ask for a code, then enter it. */
enum class LoginMode { LOGIN, FORGOT_REQUEST, FORGOT_ENTER }

private const val CODE_LENGTH = 6
private const val MIN_PASSWORD_LENGTH = 6

data class LoginState(
    val username: String = "",
    val password: String = "",
    val code: String = "",
    val newPassword: String = "",
    val mode: LoginMode = LoginMode.LOGIN,
    val passwordVisible: Boolean = false,
    val isLoading: Boolean = false,
    /** Keep the session for the next launch; false (default) = sign in every time. */
    val rememberMe: Boolean = false,
    /** Remembered sessions only: gate the next launch behind a biometric pass. */
    val biometricLogin: Boolean = false,
    /** The device can actually show a prompt - probed once; gates the checkbox. */
    val biometricsAvailable: Boolean = false,
    val errorMessage: UiText? = null,
    val infoMessage: UiText? = null,
    /** >0 while the reset lockout counts down; submit is blocked until it hits 0. */
    val lockSeconds: Long = 0,
) {
    val canSubmit: Boolean
        get() = !isLoading && when (mode) {
            LoginMode.LOGIN -> username.isNotBlank() && password.isNotBlank()
            LoginMode.FORGOT_REQUEST -> username.isNotBlank()
            LoginMode.FORGOT_ENTER ->
                code.length == CODE_LENGTH && newPassword.length >= MIN_PASSWORD_LENGTH && lockSeconds == 0L
        }
}

sealed interface LoginIntent {
    data class UsernameChanged(val value: String) : LoginIntent
    data class PasswordChanged(val value: String) : LoginIntent
    data class CodeChanged(val value: String) : LoginIntent
    data class NewPasswordChanged(val value: String) : LoginIntent
    data object TogglePasswordVisibility : LoginIntent
    data class RememberMeChanged(val value: Boolean) : LoginIntent
    data class BiometricLoginChanged(val value: Boolean) : LoginIntent
    data object StartForgot : LoginIntent
    data object BackToLogin : LoginIntent
    data object Submit : LoginIntent
}

sealed interface LoginEffect {
    data object LoggedIn : LoginEffect
}

@Stable
class LoginViewModel(
    private val repository: AuthRepository,
    private val biometricAuth: BiometricAuth,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state by _state.toComposeState(viewModelScope)

    init {
        // Probe once; the biometric checkbox only exists where a real prompt can.
        viewModelScope.launch {
            val available = runCatching { biometricAuth.available() }.getOrDefault(false)
            _state.update { it.copy(biometricsAvailable = available) }
        }
    }

    private val eventChannel = Channel<LoginEffect>()
    val events = eventChannel.receiveAsFlow()

    private var countdownJob: Job? = null

    fun onAction(intent: LoginIntent) {
        when (intent) {
            is LoginIntent.UsernameChanged -> _state.update { it.copy(username = intent.value) }
            is LoginIntent.PasswordChanged -> _state.update { it.copy(password = intent.value) }
            is LoginIntent.CodeChanged -> _state.update { it.copy(code = intent.value.filter { c -> c.isDigit() }.take(CODE_LENGTH)) }
            is LoginIntent.NewPasswordChanged -> _state.update { it.copy(newPassword = intent.value) }
            LoginIntent.TogglePasswordVisibility -> _state.update { it.copy(passwordVisible = !it.passwordVisible) }
            is LoginIntent.RememberMeChanged -> _state.update {
                // Un-remembering also drops the biometric gate - it rides the
                // persisted session, which will no longer exist.
                it.copy(rememberMe = intent.value, biometricLogin = it.biometricLogin && intent.value)
            }
            is LoginIntent.BiometricLoginChanged -> _state.update { it.copy(biometricLogin = intent.value) }
            LoginIntent.StartForgot -> _state.update {
                it.copy(mode = LoginMode.FORGOT_REQUEST, password = "", code = "", newPassword = "", errorMessage = null, infoMessage = null)
            }
            LoginIntent.BackToLogin -> {
                countdownJob?.cancel()
                _state.update {
                    it.copy(mode = LoginMode.LOGIN, code = "", newPassword = "", errorMessage = null, infoMessage = null, lockSeconds = 0)
                }
            }
            LoginIntent.Submit -> when (_state.value.mode) {
                LoginMode.LOGIN -> submitLogin()
                LoginMode.FORGOT_REQUEST -> requestCode()
                LoginMode.FORGOT_ENTER -> submitReset()
            }
        }
    }

    private fun submitLogin() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = repository.login(
                s.username,
                s.password,
                remember = s.rememberMe,
                biometricLogin = s.rememberMe && s.biometricLogin,
            )) {
                is DataResult.Success -> eventChannel.send(LoginEffect.LoggedIn)
                is DataResult.Failure -> _state.update { it.copy(errorMessage = result.error.toUiText()) }
            }
            _state.update { it.copy(isLoading = false) }
        }
    }

    private fun requestCode() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            when (val result = repository.forgotPassword(s.username)) {
                // Success or not, we move on and say the same thing (anti-enumeration);
                // only a transport failure surfaces.
                is DataResult.Success -> _state.update {
                    it.copy(mode = LoginMode.FORGOT_ENTER, infoMessage = UiText.Res(StringKey.LOGIN_CODE_SENT))
                }
                is DataResult.Failure -> _state.update { it.copy(errorMessage = result.error.toUiText()) }
            }
            _state.update { it.copy(isLoading = false) }
        }
    }

    private fun submitReset() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            when (val result = repository.resetPassword(s.username, s.code, s.newPassword)) {
                is DataResult.Failure -> _state.update { it.copy(errorMessage = result.error.toUiText()) }
                is DataResult.Success -> when (val outcome = result.data) {
                    ResetOutcome.Success -> {
                        countdownJob?.cancel()
                        _state.update {
                            it.copy(
                                mode = LoginMode.LOGIN, password = "", code = "", newPassword = "",
                                lockSeconds = 0, infoMessage = UiText.Res(StringKey.LOGIN_RESET_DONE),
                            )
                        }
                    }
                    ResetOutcome.Expired -> _state.update { it.copy(errorMessage = UiText.Res(StringKey.LOGIN_RESET_EXPIRED)) }
                    is ResetOutcome.Invalid -> {
                        _state.update { it.copy(errorMessage = UiText.Res(StringKey.LOGIN_RESET_INVALID)) }
                        outcome.retryAfterSeconds?.let { startCountdown(it) }
                    }
                    // Already locked: no "wrong code" note, just the countdown.
                    is ResetOutcome.Locked -> startCountdown(outcome.retryAfterSeconds)
                }
            }
            _state.update { it.copy(isLoading = false) }
        }
    }

    /** Ticks [seconds] down to zero, re-enabling submit; cancels any prior countdown. */
    private fun startCountdown(seconds: Long) {
        countdownJob?.cancel()
        _state.update { it.copy(lockSeconds = seconds) }
        countdownJob = viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                delay(1000)
                remaining -= 1
                _state.update { it.copy(lockSeconds = remaining.coerceAtLeast(0)) }
            }
        }
    }
}
