package eu.anifantakis.commercials.feature.auth.presentation.screens.api_tokens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppDialog
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.design_system.components.rememberClipboardCopy
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.domain.model.ApiToken
import eu.anifantakis.commercials.feature.auth.domain.model.CreatedApiToken
import eu.anifantakis.commercials.feature.auth.presentation.toUiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Immutable
data class ApiTokensState(
    val tokens: ImmutableList<ApiToken> = persistentListOf(),
    val newName: String = "",
    val busy: Boolean = false,
    /** Set right after a mint: the raw secret + MCP URL, shown ONCE. */
    val created: CreatedApiToken? = null,
    val error: UiText? = null,
) {
    val canCreate: Boolean get() = !busy && newName.isNotBlank()
}

sealed interface ApiTokensIntent {
    data object Load : ApiTokensIntent
    data class NameChanged(val value: String) : ApiTokensIntent
    data object Create : ApiTokensIntent
    data class Revoke(val id: Long) : ApiTokensIntent
    data object DismissCreated : ApiTokensIntent
}

@Stable
class ApiTokensViewModel(
    private val repository: AuthRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(ApiTokensState())
    val state by _state.toComposeState(viewModelScope)

    fun onAction(intent: ApiTokensIntent) {
        when (intent) {
            ApiTokensIntent.Load -> load()
            is ApiTokensIntent.NameChanged -> _state.update { it.copy(newName = intent.value) }
            ApiTokensIntent.Create -> create()
            is ApiTokensIntent.Revoke -> revoke(intent.id)
            ApiTokensIntent.DismissCreated -> _state.update { it.copy(created = null) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            when (val result = repository.listApiTokens()) {
                is DataResult.Success -> _state.update { it.copy(tokens = result.data.toImmutableList()) }
                is DataResult.Failure -> _state.update { it.copy(error = result.error.toUiText()) }
            }
        }
    }

    private fun create() {
        val s = _state.value
        if (!s.canCreate) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.createApiToken(s.newName.trim())) {
                is DataResult.Success -> {
                    _state.update { it.copy(created = result.data, newName = "", busy = false) }
                    load()
                }
                is DataResult.Failure -> _state.update { it.copy(busy = false, error = result.error.toUiText()) }
            }
        }
    }

    private fun revoke(id: Long) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.revokeApiToken(id)) {
                is DataResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    load()
                }
                is DataResult.Failure -> _state.update { it.copy(busy = false, error = result.error.toUiText()) }
            }
        }
    }
}

/**
 * Self-service MCP / API personal access tokens. Mint a NON-EXPIRING token that
 * acts with the caller's own access, copy it (shown once) + the MCP config, and
 * revoke old ones. Reached from Preferences.
 */
@Composable
fun ApiTokensDialogRoot(
    onDismiss: () -> Unit,
    viewModel: ApiTokensViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.onAction(ApiTokensIntent.Load) }
    ApiTokensDialog(state = viewModel.state, onIntent = viewModel::onAction, onDismiss = onDismiss)
}

private fun mcpConfigSnippet(created: CreatedApiToken): String =
    "{\n  \"url\": \"${created.mcpUrl}\",\n  \"headers\": { \"Authorization\": \"Bearer ${created.token}\" }\n}"

@Composable
private fun ApiTokensDialog(
    state: ApiTokensState,
    onIntent: (ApiTokensIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val copy = rememberClipboardCopy()
    val created = state.created
    AppDialog(
        title = Strings[StringKey.MCP_TOKENS_TITLE],
        onDismiss = onDismiss,
        confirmText = Strings[StringKey.COMMON_CLOSE],
        onConfirm = { if (created != null) onIntent(ApiTokensIntent.DismissCreated) else onDismiss() },
    ) {
        if (created != null) {
            // Shown ONCE: the secret, the URL, and a ready config to paste.
            AppText(Strings[StringKey.MCP_TOKENS_CREATED_HINT], AppTextStyle.BODY_STRONG)
            CopyRow(label = Strings[StringKey.MCP_TOKENS_TOKEN_LABEL], value = created.token, onCopy = { copy(created.token) })
            CopyRow(label = Strings[StringKey.MCP_TOKENS_URL_LABEL], value = created.mcpUrl, onCopy = { copy(created.mcpUrl) })
            AppButton(
                text = Strings[StringKey.MCP_TOKENS_COPY_CONFIG],
                onClick = { copy(mcpConfigSnippet(created)) },
                variant = AppButtonVariant.SECONDARY,
            )
        } else {
            AppText(Strings[StringKey.MCP_TOKENS_HINT], AppTextStyle.NOTE)
            if (state.tokens.isEmpty()) {
                AppText(Strings[StringKey.MCP_TOKENS_EMPTY], AppTextStyle.NOTE)
            } else {
                Column {
                    state.tokens.forEach { token ->
                        TokenRow(token = token, enabled = !state.busy, onRevoke = { onIntent(ApiTokensIntent.Revoke(token.id)) })
                    }
                }
            }
            AppTextField(
                value = state.newName,
                onValueChange = { onIntent(ApiTokensIntent.NameChanged(it)) },
                label = Strings[StringKey.MCP_TOKENS_NAME],
                enabled = !state.busy,
            )
            AppButton(
                text = Strings[StringKey.MCP_TOKENS_GENERATE],
                onClick = { onIntent(ApiTokensIntent.Create) },
                enabled = state.canCreate,
                busy = state.busy,
                fillMaxWidth = true,
            )
        }
        state.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, onCopy: () -> Unit) {
    Column {
        AppText(label, AppTextStyle.FIELD_LABEL)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
            AppText(value, AppTextStyle.MONO, modifier = Modifier.weight(1f))
            AppButton(text = Strings[StringKey.COMMON_COPY], onClick = onCopy, variant = AppButtonVariant.SECONDARY)
        }
    }
}

@Composable
private fun TokenRow(token: ApiToken, enabled: Boolean, onRevoke: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AppText(token.name, AppTextStyle.BODY_STRONG)
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
