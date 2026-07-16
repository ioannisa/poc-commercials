package eu.anifantakis.commercials.feature.user_management.presentation.screens.admin_mcp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCheckboxRow
import eu.anifantakis.commercials.core.presentation.design_system.components.AppDialog
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.user_management.domain.AdminApiToken
import eu.anifantakis.commercials.feature.user_management.domain.UserManagementRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Immutable
data class AdminMcpState(
    val enabled: Boolean = true,
    val tokenCount: Int = 0,
    val tokens: ImmutableList<AdminApiToken> = persistentListOf(),
    val busy: Boolean = false,
    val error: UiText? = null,
)

sealed interface AdminMcpIntent {
    data object Load : AdminMcpIntent
    data class SetEnabled(val enabled: Boolean) : AdminMcpIntent
    data class Revoke(val id: Long) : AdminMcpIntent
}

@Stable
class AdminMcpViewModel(
    private val repository: UserManagementRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(AdminMcpState())
    val state by _state.toComposeState(viewModelScope)

    fun onAction(intent: AdminMcpIntent) {
        when (intent) {
            AdminMcpIntent.Load -> load()
            is AdminMcpIntent.SetEnabled -> setEnabled(intent.enabled)
            is AdminMcpIntent.Revoke -> revoke(intent.id)
        }
    }

    private fun load() {
        viewModelScope.launch {
            when (val settings = repository.getMcpSettings()) {
                is DataResult.Success -> _state.update { it.copy(enabled = settings.data.enabled, tokenCount = settings.data.tokenCount) }
                is DataResult.Failure -> _state.update { it.copy(error = settings.error.toUiText()) }
            }
            when (val tokens = repository.listAllApiTokens()) {
                is DataResult.Success -> _state.update { it.copy(tokens = tokens.data.toImmutableList()) }
                is DataResult.Failure -> _state.update { it.copy(error = tokens.error.toUiText()) }
            }
        }
    }

    private fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.setMcpEnabled(enabled)) {
                is DataResult.Success -> { _state.update { it.copy(enabled = enabled, busy = false) } }
                is DataResult.Failure -> _state.update { it.copy(busy = false, error = result.error.toUiText()) }
            }
        }
    }

    private fun revoke(id: Long) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.revokeApiToken(id)) {
                is DataResult.Success -> { _state.update { it.copy(busy = false) }; load() }
                is DataResult.Failure -> _state.update { it.copy(busy = false, error = result.error.toUiText()) }
            }
        }
    }
}

/**
 * Admin oversight of every user's MCP/API tokens: the global kill switch, the
 * live count, and per-token revoke. Super-admin only (the endpoints 403 others).
 */
@Composable
fun AdminMcpDialogRoot(
    onDismiss: () -> Unit,
    viewModel: AdminMcpViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.onAction(AdminMcpIntent.Load) }
    AdminMcpDialog(state = viewModel.state, onIntent = viewModel::onAction, onDismiss = onDismiss)
}

@Composable
private fun AdminMcpDialog(
    state: AdminMcpState,
    onIntent: (AdminMcpIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        title = Strings[StringKey.ADMIN_MCP_TITLE],
        onDismiss = onDismiss,
        confirmText = Strings[StringKey.COMMON_CLOSE],
        onConfirm = onDismiss,
    ) {
        AppCheckboxRow(
            checked = state.enabled,
            onCheckedChange = { onIntent(AdminMcpIntent.SetEnabled(it)) },
            label = Strings[StringKey.ADMIN_MCP_ENABLED],
            enabled = !state.busy,
        )
        AppText(
            Strings[StringKey.ADMIN_MCP_TOKENS_HEADER].withArgs(listOf(state.tokenCount)),
            AppTextStyle.BODY_STRONG,
        )
        if (state.tokens.isEmpty()) {
            AppText(Strings[StringKey.ADMIN_MCP_NO_TOKENS], AppTextStyle.NOTE)
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(state.tokens, key = { it.id }) { token ->
                    TokenRow(token = token, enabled = !state.busy, onRevoke = { onIntent(AdminMcpIntent.Revoke(token.id)) })
                }
            }
        }
        state.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }
    }
}

@Composable
private fun TokenRow(token: AdminApiToken, enabled: Boolean, onRevoke: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AppText("${token.username} · ${token.name}", AppTextStyle.BODY)
            AppText(
                token.lastUsedAt?.let { Strings[StringKey.MCP_TOKENS_LAST_USED].withArgs(listOf(it)) }
                    ?: Strings[StringKey.MCP_TOKENS_NEVER_USED],
                AppTextStyle.TINY,
            )
        }
        AppButton(
            text = Strings[StringKey.MCP_TOKENS_REVOKE],
            onClick = onRevoke,
            variant = AppButtonVariant.TEXT,
            enabled = enabled,
        )
    }
}
