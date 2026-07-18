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
import eu.anifantakis.commercials.feature.user_management.domain.AiUsageEntry
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
    /** Global switch: every NEW OAuth grant needs super-admin approval. */
    val adminApprovalRequired: Boolean = false,
    val tokenCount: Int = 0,
    val oauthGrantCount: Int = 0,
    val tokens: ImmutableList<AdminApiToken> = persistentListOf(),
    val oauthGrants: ImmutableList<AdminOAuthToken> = persistentListOf(),
    val users: ImmutableList<ManagedUser> = persistentListOf(),
    /** Per-user AI-chat token usage (aggregated), most recently used first. */
    val aiUsage: ImmutableList<AiUsageEntry> = persistentListOf(),
    /** When set, the change-role picker is open for this workstation's token. */
    val reassignFor: AdminApiToken? = null,
    /** Revoke is destructive - it arms one of these and a confirm dialog fires it. */
    val pendingRevokeToken: AdminApiToken? = null,
    val pendingRevokeGrant: AdminOAuthToken? = null,
    val busy: Boolean = false,
    val error: UiText? = null,
)

sealed interface AdminMcpIntent {
    data object Load : AdminMcpIntent
    data class SetEnabled(val enabled: Boolean) : AdminMcpIntent
    data class SetAdminApproval(val required: Boolean) : AdminMcpIntent
    data class RequestRevoke(val token: AdminApiToken) : AdminMcpIntent
    data class RequestRevokeOAuth(val grant: AdminOAuthToken) : AdminMcpIntent
    data object CancelRevoke : AdminMcpIntent
    data class Revoke(val id: Long) : AdminMcpIntent
    data class RevokeOAuth(val id: Long) : AdminMcpIntent
    data class ApproveOAuth(val id: Long) : AdminMcpIntent
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
            is AdminMcpIntent.SetAdminApproval -> setAdminApproval(intent.required)
            is AdminMcpIntent.RequestRevoke -> _state.update { it.copy(pendingRevokeToken = intent.token) }
            is AdminMcpIntent.RequestRevokeOAuth -> _state.update { it.copy(pendingRevokeGrant = intent.grant) }
            AdminMcpIntent.CancelRevoke ->
                _state.update { it.copy(pendingRevokeToken = null, pendingRevokeGrant = null) }
            is AdminMcpIntent.Revoke -> revoke(intent.id)
            is AdminMcpIntent.RevokeOAuth -> revokeOAuth(intent.id)
            is AdminMcpIntent.ApproveOAuth -> approveOAuth(intent.id)
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
                        adminApprovalRequired = settings.data.adminApprovalRequired,
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
            when (val usage = repository.aiUsage()) {
                is DataResult.Success -> _state.update { it.copy(aiUsage = usage.data.toImmutableList()) }
                is DataResult.Failure -> _state.update { it.copy(error = usage.error.toUiText()) }
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

    private fun setAdminApproval(required: Boolean) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.setOauthAdminApproval(required)) {
                is DataResult.Success -> _state.update { it.copy(adminApprovalRequired = required, busy = false) }
                is DataResult.Failure -> _state.update { it.copy(busy = false, error = result.error.toUiText()) }
            }
        }
    }

    private fun revoke(id: Long) {
        _state.update { it.copy(busy = true, error = null, pendingRevokeToken = null) }
        viewModelScope.launch {
            when (val result = repository.revokeApiToken(id)) {
                is DataResult.Success -> { _state.update { it.copy(busy = false) }; load() }
                is DataResult.Failure -> _state.update { it.copy(busy = false, error = result.error.toUiText()) }
            }
        }
    }

    private fun revokeOAuth(id: Long) {
        _state.update { it.copy(busy = true, error = null, pendingRevokeGrant = null) }
        viewModelScope.launch {
            when (val result = repository.revokeOAuthToken(id)) {
                is DataResult.Success -> { _state.update { it.copy(busy = false) }; load() }
                is DataResult.Failure -> _state.update { it.copy(busy = false, error = result.error.toUiText()) }
            }
        }
    }

    private fun approveOAuth(id: Long) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.approveOAuthToken(id)) {
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
 * Admin oversight of all MCP access: the global kill switch (covers PATs AND
 * OAuth grants), live counts, per-workstation PAT Revoke or Change role
 * (repoint the token to another user without touching that machine), and every
 * user's OAuth grants (native-connector logins - revoke only, a grant IS its
 * user's identity). Super-admin only (endpoints 403 others).
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
            // Global gate: new OAuth grants stay pending until approved here.
            AppCheckboxRow(
                checked = state.adminApprovalRequired,
                onCheckedChange = { onIntent(AdminMcpIntent.SetAdminApproval(it)) },
                label = Strings[StringKey.ADMIN_MCP_APPROVAL_TOGGLE],
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
                            onRevoke = { onIntent(AdminMcpIntent.RequestRevoke(token)) },
                            onChangeRole = { onIntent(AdminMcpIntent.Reassign(token)) },
                        )
                    }
                }
            }

            // OAuth grants: native-connector logins (Claude, ChatGPT, ...), one
            // per user × client app. No workstation, no change-role - the grant
            // IS a user's own identity; admin actions are revoke and approve.
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
                            onRevoke = { onIntent(AdminMcpIntent.RequestRevokeOAuth(grant)) },
                            onApprove = { onIntent(AdminMcpIntent.ApproveOAuth(grant.id)) },
                        )
                    }
                }
            }

            // AI-chat token metering: lifetime totals per user x provider x
            // model - the operator's per-user cost picture.
            AppText(Strings[StringKey.ADMIN_MCP_AI_USAGE_HEADER], AppTextStyle.BODY_STRONG)
            if (state.aiUsage.isEmpty()) {
                AppText(Strings[StringKey.ADMIN_MCP_NO_AI_USAGE], AppTextStyle.NOTE)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(state.aiUsage, key = { it.username + it.provider + it.model }) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = UIConst.paddingHairline),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppText(row.username, AppTextStyle.TABLE_CELL_STRONG, modifier = Modifier.weight(0.9f))
                            AppText(row.provider + " / " + row.model, AppTextStyle.TABLE_CELL, modifier = Modifier.weight(1.6f))
                            AppText("in " + row.inputTokens + " | out " + row.outputTokens + " | x" + row.requests, AppTextStyle.TABLE_CELL, modifier = Modifier.weight(1.3f))
                        }
                    }
                }
            }
        }
        state.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }
    }

    state.pendingRevokeToken?.let { token ->
        RevokeConfirmDialog(
            name = token.workstationName,
            onConfirm = { onIntent(AdminMcpIntent.Revoke(token.id)) },
            onCancel = { onIntent(AdminMcpIntent.CancelRevoke) },
        )
    }
    state.pendingRevokeGrant?.let { grant ->
        RevokeConfirmDialog(
            name = grant.connectedAccount?.let { "${grant.clientName} — $it" } ?: grant.clientName,
            onConfirm = { onIntent(AdminMcpIntent.RevokeOAuth(grant.id)) },
            onCancel = { onIntent(AdminMcpIntent.CancelRevoke) },
        )
    }
}

/** Revoke is destructive and instant - always a confirmation step first. */
@Composable
private fun RevokeConfirmDialog(name: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AppDialog(
        title = Strings[StringKey.MCP_REVOKE_CONFIRM_TITLE].withArgs(listOf(name)),
        onDismiss = onCancel,
        confirmText = Strings[StringKey.MCP_TOKENS_REVOKE],
        onConfirm = onConfirm,
        dismissText = Strings[StringKey.COMMON_CANCEL],
        destructive = true,
    ) {
        AppText(Strings[StringKey.MCP_REVOKE_CONFIRM_TEXT], AppTextStyle.NOTE)
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
    onApprove: () -> Unit,
) {
    val pending = !grant.userApproved || !grant.adminApproved
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // Title carries the self-declared identity: "Claude — you@example.com"
            // is what tells two grants of the same client apart.
            AppText(
                grant.connectedAccount?.let { "${grant.clientName} — $it" } ?: grant.clientName,
                AppTextStyle.BODY_STRONG,
            )
            AppText("${grant.username} · ${grant.createdAt}", AppTextStyle.TINY)
            AppText(
                grant.lastUsedAt?.let { Strings[StringKey.MCP_TOKENS_LAST_USED].withArgs(listOf(it)) }
                    ?: Strings[StringKey.MCP_TOKENS_NEVER_USED],
                AppTextStyle.TINY,
            )
            // Consent forensics (IP · browser) - unspoofable, unlike the label.
            val forensics = listOfNotNull(grant.consentIp, grant.consentUserAgent?.take(64))
            if (forensics.isNotEmpty()) {
                AppText(
                    Strings[StringKey.ADMIN_MCP_CONSENT_FROM].withArgs(listOf(forensics.joinToString(" · "))),
                    AppTextStyle.TINY,
                )
            }
            when {
                !grant.adminApproved -> AppText(Strings[StringKey.MCP_OAUTH_PENDING_ADMIN], AppTextStyle.ERROR_NOTE)
                !grant.userApproved -> AppText(Strings[StringKey.ADMIN_MCP_PENDING_USER], AppTextStyle.ERROR_NOTE)
            }
        }
        // Approve force-clears BOTH gates - the unblock path when the user's
        // approval e-mail never arrived.
        if (pending) {
            AppButton(
                text = Strings[StringKey.ADMIN_MCP_APPROVE],
                onClick = onApprove,
                variant = AppButtonVariant.TEXT,
                enabled = enabled,
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
