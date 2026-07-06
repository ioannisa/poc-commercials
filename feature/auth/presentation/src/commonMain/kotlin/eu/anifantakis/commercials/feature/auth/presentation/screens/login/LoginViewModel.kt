package eu.anifantakis.commercials.feature.auth.presentation.screens.login

import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.helper.UiText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.presentation.toUiText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginState(
    val username: String = "",
    val password: String = "",
    val recoveryCode: String = "",
    val recoveryMode: Boolean = false,
    val passwordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: UiText? = null,
    val infoMessage: UiText? = null,
) {
    val canSubmit: Boolean
        get() = !isLoading && username.isNotBlank() && password.isNotBlank() &&
            (!recoveryMode || recoveryCode.isNotBlank())
}

sealed interface LoginIntent {
    data class UsernameChanged(val value: String) : LoginIntent
    data class PasswordChanged(val value: String) : LoginIntent
    data class RecoveryCodeChanged(val value: String) : LoginIntent
    data object TogglePasswordVisibility : LoginIntent
    data object ToggleRecoveryMode : LoginIntent
    data object Submit : LoginIntent
}

sealed interface LoginEffect {
    data object LoggedIn : LoginEffect
}

@Stable
class LoginViewModel(
    private val repository: AuthRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state by _state.toComposeState(viewModelScope)

    private val eventChannel = Channel<LoginEffect>()
    val events = eventChannel.receiveAsFlow()

    fun onAction(intent: LoginIntent) {
        when (intent) {
            is LoginIntent.UsernameChanged -> _state.update { it.copy(username = intent.value) }
            is LoginIntent.PasswordChanged -> _state.update { it.copy(password = intent.value) }
            is LoginIntent.RecoveryCodeChanged -> _state.update { it.copy(recoveryCode = intent.value) }
            LoginIntent.TogglePasswordVisibility ->
                _state.update { it.copy(passwordVisible = !it.passwordVisible) }
            LoginIntent.ToggleRecoveryMode -> _state.update {
                it.copy(
                    recoveryMode = !it.recoveryMode,
                    password = "",
                    errorMessage = null,
                    infoMessage = null,
                )
            }
            LoginIntent.Submit -> if (_state.value.recoveryMode) submitRecovery() else submitLogin()
        }
    }

    private fun submitLogin() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = repository.login(s.username, s.password)) {
                is DataResult.Success ->
                    eventChannel.send(LoginEffect.LoggedIn)
                is DataResult.Failure ->
                    _state.update { it.copy(errorMessage = result.error.toUiText()) }
            }
            _state.update { it.copy(isLoading = false) }
        }
    }

    private fun submitRecovery() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = repository.recoverPassword(s.username, s.recoveryCode, s.password)) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        recoveryMode = false,
                        password = "",
                        recoveryCode = "",
                        infoMessage = UiText.Res(StringKey.LOGIN_RESET_DONE),
                    )
                }
                is DataResult.Failure ->
                    _state.update { it.copy(errorMessage = result.error.toUiText()) }
            }
            _state.update { it.copy(isLoading = false) }
        }
    }
}
