package eu.anifantakis.commercials.feature.user_management.presentation.screens.admin_mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
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
import eu.anifantakis.commercials.feature.user_management.domain.AdminOAuthToken
import eu.anifantakis.commercials.feature.user_management.domain.ManagedUser
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
    val oauthGrantCount: Int = 0,
    val tokens: ImmutableList<AdminApiToken> = persistentListOf(),
    val oauthGrants: ImmutableList<AdminOAuthToken> = persistentListOf(),
    val users: ImmutableList<ManagedUser> = persistentListOf(),
    /** When set, the change-role picker is open for this workstation's token. */
    val reassignFor: AdminApiToken? = null,
    val busy: Boolean = false,
    val error: UiText? = null,
)

sealed interface AdminMcpIntent {
    data object Load : AdminMcpIntent
    data class SetEnabled(val enabled: Boolean) : AdminMcpIntent
    data class Revoke(val id: Long) : AdminMcpIntent
    data class RevokeOAuth(val id: Long) : AdminMcpIntent
    data class Reassign(val token: AdminApiToken) : AdminMcpIntent
    data class ConfirmReassign(val workstation: String, val userId: Long) : AdminMcpIntent
    data object CancelReassign : AdminMcpIntent
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
            is AdminMcpIntent.RevokeOAuth -> revokeOAuth(intent.id)
            is AdminMcpIntent.Reassign -> _state.update { it.copy(reassignFor = intent.token) }
            AdminMcpIntent.CancelReassign -> _state.update { it.copy(reassignFor = null) }
            is AdminMcpIntent.ConfirmReassign -> reassign(intent.workstation, intent.userId)
        }
    }

    private fun load() {
        viewModelScope.launch {
            when (val settings = repository.getMcpSettings()) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        enabled = settings.data.enabled,
                        tokenCount = settings.data.tokenCount,
                        oauthGrantCount = settings.data.oauthGrantCount,
                    )
                }
                is DataResult.Failure -> _state.update { it.copy(error = settings.error.toUiText()) }
            }
            when (val tokens = repository.listAllApiTokens()) {
                is DataResult.Success -> _state.update { it.copy(tokens = tokens.data.toImmutableList()) }
                is DataResult.Failure -> _state.update { it.copy(error = tokens.error.toUiText()) }
            }
            when (val grants = repository.listAllOAuthTokens()) {
                is DataResult.Success -> _state.update { it.copy(oauthGrants = grants.data.toImmutableList()) }
                is DataResult.Failure -> _state.update { it.copy(error = grants.error.toUiText()) }
            }
            when (val users = repository.listUsers()) {
                is DataResult.Success -> _state.update { it.copy(users = users.data.toImmutableList()) }
                is DataResult.Failure -> _state.update { it.copy(error = users.error.toUiText()) }
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

    private fun revokeOAuth(id: Long) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.revokeOAuthToken(id)) {
                is DataResult.Success -> { _state.update { it.copy(busy = false) }; load() }
                is DataResult.Failure -> _state.update { it.copy(busy = false, error = result.error.toUiText()) }
            }
        }
    }

    private fun reassign(workstation: String, userId: Long) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.reassignApiToken(workstation, userId)) {
                is DataResult.Success -> { _state.update { it.copy(busy = false, reassignFor = null) }; load() }
                is DataResult.Failure -> _state.update { it.copy(busy = false, error = result.error.toUiText()) }
            }
        }
    }
}

/**
 * Admin oversight of every workstation's MCP token: the global kill switch, the
 * live count, and per-workstation Revoke or Change role (repoint the token to
 * another user without touching that machine). Super-admin only (endpoints 403
 * others).
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
        val reassignFor = state.reassignFor
        if (reassignFor != null) {
            ReassignPicker(
                token = reassignFor,
                users = state.users,
                enabled = !state.busy,
                onPick = { onIntent(AdminMcpIntent.ConfirmReassign(reassignFor.workstationName, it.id)) },
                onCancel = { onIntent(AdminMcpIntent.CancelReassign) },
            )
        } else {
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
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(state.tokens, key = { it.id }) { token ->
                        TokenRow(
                            token = token,
                            enabled = !state.busy,
                            onRevoke = { onIntent(AdminMcpIntent.Revoke(token.id)) },
                            onChangeRole = { onIntent(AdminMcpIntent.Reassign(token)) },
                        )
                    }
                }
            }

            // OAuth grants: native-connector logins (Claude, ChatGPT, ...), one
            // per user × client app. No workstation, no change-role - the grant
            // IS a user's own identity; the only admin action is revoke.
            AppText(
                Strings[StringKey.ADMIN_MCP_OAUTH_HEADER].withArgs(listOf(state.oauthGrantCount)),
                AppTextStyle.BODY_STRONG,
            )
            if (state.oauthGrants.isEmpty()) {
                AppText(Strings[StringKey.ADMIN_MCP_NO_OAUTH], AppTextStyle.NOTE)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(state.oauthGrants, key = { it.id }) { grant ->
                        OAuthGrantRow(
                            grant = grant,
                            enabled = !state.busy,
                            onRevoke = { onIntent(AdminMcpIntent.RevokeOAuth(grant.id)) },
                        )
                    }
                }
            }
        }
        state.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }
    }
}

@Composable
private fun TokenRow(
    token: AdminApiToken,
    enabled: Boolean,
    onRevoke: () -> Unit,
    onChangeRole: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AppText(token.workstationName, AppTextStyle.BODY_STRONG)
            AppText("${token.username} · ${token.userRole}", AppTextStyle.TINY)
            AppText(
                token.lastUsedAt?.let { Strings[StringKey.MCP_TOKENS_LAST_USED].withArgs(listOf(it)) }
                    ?: Strings[StringKey.MCP_TOKENS_NEVER_USED],
                AppTextStyle.TINY,
            )
        }
        AppButton(
            text = Strings[StringKey.ADMIN_MCP_CHANGE_ROLE],
            onClick = onChangeRole,
            variant = AppButtonVariant.TEXT,
            enabled = enabled,
        )
        AppButton(
            text = Strings[StringKey.MCP_TOKENS_REVOKE],
            onClick = onRevoke,
            variant = AppButtonVariant.TEXT,
            enabled = enabled,
        )
    }
}

@Composable
private fun OAuthGrantRow(
    grant: AdminOAuthToken,
    enabled: Boolean,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AppText(grant.clientName, AppTextStyle.BODY_STRONG)
            AppText("${grant.username} · ${grant.createdAt}", AppTextStyle.TINY)
            AppText(
                grant.lastUsedAt?.let { Strings[StringKey.MCP_TOKENS_LAST_USED].withArgs(listOf(it)) }
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

@Composable
private fun ReassignPicker(
    token: AdminApiToken,
    users: ImmutableList<ManagedUser>,
    enabled: Boolean,
    onPick: (ManagedUser) -> Unit,
    onCancel: () -> Unit,
) {
    AppText(
        Strings[StringKey.ADMIN_MCP_PICK_USER].withArgs(listOf(token.workstationName)),
        AppTextStyle.BODY_STRONG,
    )
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
        items(users, key = { it.id }) { user ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    AppText(user.username, AppTextStyle.BODY_STRONG)
                    AppText(user.displayName, AppTextStyle.TINY)
                }
                AppButton(
                    text = Strings[StringKey.ADMIN_MCP_ASSIGN],
                    onClick = { onPick(user) },
                    variant = AppButtonVariant.TEXT,
                    enabled = enabled && user.username != token.username,
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = UIConst.paddingSmall),
        horizontalArrangement = Arrangement.End,
    ) {
        AppButton(
            text = Strings[StringKey.COMMON_CANCEL],
            onClick = onCancel,
            variant = AppButtonVariant.SECONDARY,
            enabled = enabled,
        )
    }
}
