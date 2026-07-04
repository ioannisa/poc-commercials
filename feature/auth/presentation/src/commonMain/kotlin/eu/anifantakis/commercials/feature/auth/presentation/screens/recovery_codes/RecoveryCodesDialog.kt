package eu.anifantakis.commercials.feature.auth.presentation.screens.recovery_codes

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Stable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.presentation.toDisplayMessage
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
    val error: String? = null,
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
                    it.copy(error = result.error.toDisplayMessage(), busy = false)
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
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("Recovery codes") },
        text = {
            Column {
                if (state.codes == null) {
                    Text(
                        "Generate 6 one-time codes for the \"forgot password\" flow. " +
                            "They are shown ONCE - store them somewhere safe. " +
                            "Generating new codes invalidates all previous ones.",
                        fontSize = 13.sp
                    )
                } else {
                    Text(
                        "Save these now - they will never be shown again:",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    state.codes.forEach { code ->
                        Text(code, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    }
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            if (state.codes == null) {
                TextButton(
                    enabled = !state.busy,
                    onClick = { onIntent(RecoveryCodesIntent.Generate) }
                ) {
                    if (state.busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else Text("Generate new codes")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("I saved them") }
            }
        },
        dismissButton = {
            if (state.codes == null) TextButton(enabled = !state.busy, onClick = onDismiss) { Text("Cancel") }
        }
    )
}
