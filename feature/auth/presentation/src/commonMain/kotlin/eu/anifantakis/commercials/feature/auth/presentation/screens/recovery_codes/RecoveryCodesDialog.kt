package eu.anifantakis.commercials.feature.auth.presentation.screens.recovery_codes

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.design_system.components.AppDialog
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.presentation.toUiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

data class RecoveryCodesState(
    /** Null until generated; shown EXACTLY ONCE (server keeps only hashes). */
    val codes: ImmutableList<String>? = null,
    val busy: Boolean = false,
    val error: UiText? = null,
)

sealed interface RecoveryCodesIntent {
    data object Generate : RecoveryCodesIntent
}

@Stable
class RecoveryCodesViewModel(
    private val repository: AuthRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(RecoveryCodesState())
    val state by _state.toComposeState(viewModelScope)

    fun onAction(intent: RecoveryCodesIntent) {
        when (intent) {
            RecoveryCodesIntent.Generate -> generate()
        }
    }

    private fun generate() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.regenerateRecoveryCodes()) {
                is DataResult.Success -> _state.update {
                    it.copy(codes = result.data.toImmutableList(), busy = false)
                }
                is DataResult.Failure -> _state.update {
                    it.copy(error = result.error.toUiText(), busy = false)
                }
            }
        }
    }
}

/**
 * Regenerates one-time recovery codes and shows them EXACTLY ONCE - the
 * server keeps only hashes. Old codes stop working immediately.
 */
@Composable
fun RecoveryCodesDialogRoot(
    onDismiss: () -> Unit,
    viewModel: RecoveryCodesViewModel = koinViewModel(),
) {
    RecoveryCodesDialog(state = viewModel.state, onIntent = viewModel::onAction, onDismiss = onDismiss)
}

@Composable
private fun RecoveryCodesDialog(
    state: RecoveryCodesState,
    onIntent: (RecoveryCodesIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        title = Strings[StringKey.RECOVERY_TITLE],
        onDismiss = onDismiss,
        confirmText = Strings[if (state.codes == null) StringKey.RECOVERY_GENERATE else StringKey.RECOVERY_SAVED],
        onConfirm = {
            if (state.codes == null) onIntent(RecoveryCodesIntent.Generate) else onDismiss()
        },
        dismissText = if (state.codes == null) Strings[StringKey.COMMON_CANCEL] else null,
        confirmBusy = state.busy,
    ) {
        if (state.codes == null) {
            AppText(Strings[StringKey.RECOVERY_INFO], AppTextStyle.BODY)
        } else {
            AppText(Strings[StringKey.RECOVERY_SAVE_NOW], AppTextStyle.BODY_STRONG)
            // Inner column keeps the code lines a tight block (no ladder gaps
            // between codes - only the dialog's own gap above/below the block).
            Column {
                state.codes.forEach { code ->
                    AppText(code, AppTextStyle.MONO)
                }
            }
        }
        state.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }
    }
}
