package eu.anifantakis.commercials.feature.auth.presentation.screens.change_password

import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.helper.UiText
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.presentation.design_system.components.AppDialog
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.design_system.components.AppWireframeField
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.ObserveEffects
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.presentation.toUiText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

data class ChangePasswordState(
    val current: String = "",
    val new1: String = "",
    val new2: String = "",
    val busy: Boolean = false,
    val error: UiText? = null,
) {
    val canSubmit: Boolean get() = !busy && current.isNotBlank() && new1.length >= 6 && new1 == new2
}

sealed interface ChangePasswordIntent {
    data class CurrentChanged(val value: String) : ChangePasswordIntent
    data class New1Changed(val value: String) : ChangePasswordIntent
    data class New2Changed(val value: String) : ChangePasswordIntent
    data object Submit : ChangePasswordIntent
}

sealed interface ChangePasswordEffect {
    /** Server revoked every session and the local one is cleared. */
    data object Done : ChangePasswordEffect
}

@Stable
class ChangePasswordViewModel(
    private val repository: AuthRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(ChangePasswordState())
    val state by _state.toComposeState(viewModelScope)

    private val eventChannel = Channel<ChangePasswordEffect>()
    val events = eventChannel.receiveAsFlow()

    fun onAction(intent: ChangePasswordIntent) {
        when (intent) {
            is ChangePasswordIntent.CurrentChanged -> _state.update { it.copy(current = intent.value) }
            is ChangePasswordIntent.New1Changed -> _state.update { it.copy(new1 = intent.value) }
            is ChangePasswordIntent.New2Changed -> _state.update { it.copy(new2 = intent.value) }
            ChangePasswordIntent.Submit -> submit()
        }
    }

    private fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.changePassword(s.current, s.new1)) {
                is DataResult.Success -> eventChannel.send(ChangePasswordEffect.Done)
                is DataResult.Failure -> _state.update {
                    it.copy(busy = false, error = result.error.toUiText())
                }
            }
        }
    }
}

/**
 * Self-service password change. On success the server revokes every session
 * of the user and the repository clears the local one - the session-revision
 * observer then routes the app back to Login automatically.
 */
@Composable
fun ChangePasswordDialogRoot(
    onDismiss: () -> Unit,
    mandatory: Boolean = false,
    viewModel: ChangePasswordViewModel = koinViewModel(),
) {
    ObserveEffects(viewModel.events) { effect ->
        when (effect) {
            ChangePasswordEffect.Done -> onDismiss()   // session cleared -> Login screen
        }
    }
    ChangePasswordDialog(
        state = viewModel.state,
        onIntent = viewModel::onAction,
        onDismiss = onDismiss,
        mandatory = mandatory,
    )
}

@Composable
private fun ChangePasswordDialog(
    state: ChangePasswordState,
    onIntent: (ChangePasswordIntent) -> Unit,
    onDismiss: () -> Unit,
    mandatory: Boolean = false,
) {
    AppDialog(
        title = Strings[StringKey.CHPASS_TITLE],
        // Mandatory (temp-password login): NOT escapable - no cancel, backdrop and
        // back are no-ops. Only a successful change leaves (it clears the session,
        // and the session-revision observer routes to Login).
        onDismiss = if (mandatory) ({}) else onDismiss,
        confirmText = Strings[StringKey.CHPASS_CHANGE],
        onConfirm = { onIntent(ChangePasswordIntent.Submit) },
        dismissText = if (mandatory) null else Strings[StringKey.COMMON_CANCEL],
        confirmEnabled = state.canSubmit,
        confirmBusy = state.busy,
    ) {
        if (mandatory) {
            AppText(Strings[StringKey.CHPASS_MANDATORY_NOTE], AppTextStyle.BODY_STRONG)
        }
        // Always-masked fields (no reveal toggle by design - three in a row).
        AppWireframeField(
            value = state.current,
            onValueChange = { onIntent(ChangePasswordIntent.CurrentChanged(it)) },
            label = Strings[StringKey.CHPASS_CURRENT],
            visualTransformation = PasswordVisualTransformation(),
            enabled = !state.busy,
        )
        AppWireframeField(
            value = state.new1,
            onValueChange = { onIntent(ChangePasswordIntent.New1Changed(it)) },
            label = Strings[StringKey.USER_MGMT_NEW_PASSWORD_MIN],
            visualTransformation = PasswordVisualTransformation(),
            enabled = !state.busy,
        )
        AppWireframeField(
            value = state.new2,
            onValueChange = { onIntent(ChangePasswordIntent.New2Changed(it)) },
            label = Strings[StringKey.CHPASS_REPEAT],
            visualTransformation = PasswordVisualTransformation(),
            enabled = !state.busy,
        )
        state.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }
        AppText(Strings[StringKey.CHPASS_SIGNOUT_NOTE], AppTextStyle.TINY)
    }
}
