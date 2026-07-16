package eu.anifantakis.commercials.feature.auth.presentation.screens.api_tokens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
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
import eu.anifantakis.commercials.feature.auth.domain.AuthError
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.domain.model.ApiToken
import eu.anifantakis.commercials.feature.auth.domain.model.CreatedApiToken
import eu.anifantakis.commercials.feature.auth.domain.model.WorkstationAvailability
import eu.anifantakis.commercials.feature.auth.presentation.toUiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Immutable
data class ApiTokensState(
    /** The caller's own tokens - one per workstation they've claimed. */
    val tokens: ImmutableList<ApiToken> = persistentListOf(),
    val workstation: String = "",
    /** FREE / MINE / OTHER for the current input; null while blank or unresolved. */
    val availability: WorkstationAvailability? = null,
    /** OTHER + the user asked to generate: show a takeover confirmation. */
    val pendingTakeover: Boolean = false,
    val busy: Boolean = false,
    /** Set right after a mint: the raw secret + MCP URL, shown ONCE. */
    val created: CreatedApiToken? = null,
    val error: UiText? = null,
) {
    val canGenerate: Boolean get() = !busy && workstation.isNotBlank()
}

sealed interface ApiTokensIntent {
    data object Load : ApiTokensIntent
    data class WorkstationChanged(val value: String) : ApiTokensIntent
    data object Generate : ApiTokensIntent
    data object ConfirmTakeover : ApiTokensIntent
    data object CancelTakeover : ApiTokensIntent
    data class Revoke(val id: Long) : ApiTokensIntent
    data object DismissCreated : ApiTokensIntent
}

@Stable
class ApiTokensViewModel(
    private val repository: AuthRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(ApiTokensState())
    val state by _state.toComposeState(viewModelScope)

    private var availabilityJob: Job? = null

    fun onAction(intent: ApiTokensIntent) {
        when (intent) {
            ApiTokensIntent.Load -> load()
            is ApiTokensIntent.WorkstationChanged -> onWorkstationChanged(intent.value)
            ApiTokensIntent.Generate -> onGenerate()
            ApiTokensIntent.ConfirmTakeover -> create(confirmTakeover = true)
            ApiTokensIntent.CancelTakeover -> _state.update { it.copy(pendingTakeover = false) }
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

    private fun onWorkstationChanged(value: String) {
        _state.update { it.copy(workstation = value, availability = null, pendingTakeover = false, error = null) }
        availabilityJob?.cancel()
        val name = value.trim()
        if (name.isBlank()) return
        // Debounced availability check so we don't hit the server on every keystroke.
        availabilityJob = viewModelScope.launch {
            delay(400)
            when (val result = repository.checkWorkstation(name)) {
                is DataResult.Success ->
                    // Ignore a stale response if the field moved on meanwhile.
                    if (_state.value.workstation.trim() == name) {
                        _state.update { it.copy(availability = result.data) }
                    }
                is DataResult.Failure -> Unit
            }
        }
    }

    private fun onGenerate() {
        val s = _state.value
        if (!s.canGenerate) return
        // Taking over another user's workstation needs an explicit confirmation.
        if (s.availability == WorkstationAvailability.OTHER) {
            _state.update { it.copy(pendingTakeover = true) }
            return
        }
        create(confirmTakeover = false)
    }

    private fun create(confirmTakeover: Boolean) {
        val s = _state.value
        val name = s.workstation.trim()
        if (name.isBlank() || s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.createApiToken(name, confirmTakeover)) {
                is DataResult.Success -> {
                    _state.update {
                        it.copy(
                            created = result.data,
                            workstation = "",
                            availability = null,
                            pendingTakeover = false,
                            busy = false,
                        )
                    }
                    load()
                }
                is DataResult.Failure ->
                    // A 409 means the name is another user's after all - ask to take over
                    // rather than showing a dead-end error.
                    if (result.error == AuthError.Conflict) {
                        _state.update {
                            it.copy(busy = false, availability = WorkstationAvailability.OTHER, pendingTakeover = true)
                        }
                    } else {
                        _state.update { it.copy(busy = false, pendingTakeover = false, error = result.error.toUiText()) }
                    }
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
 * Self-service MCP / API personal access tokens. Each token is bound to a
 * WORKSTATION (the machine label); at most one exists per workstation, so
 * generating for a name replaces whatever held it (rotate your own, or take over
 * another user's after a confirmation). Copy the secret (shown once) + the MCP
 * config into the client on that machine. Reached from Preferences.
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
            AppText(Strings[StringKey.MCP_TOKENS_WORKSTATION_HINT], AppTextStyle.NOTE)
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
                value = state.workstation,
                onValueChange = { onIntent(ApiTokensIntent.WorkstationChanged(it)) },
                label = Strings[StringKey.MCP_TOKENS_WORKSTATION],
                enabled = !state.busy,
            )
            availabilityLabel(state)?.let { AppText(it, AppTextStyle.NOTE) }

            if (state.pendingTakeover) {
                AppText(Strings[StringKey.MCP_TOKENS_TAKEOVER_CONFIRM], AppTextStyle.ERROR_NOTE)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
                ) {
                    AppButton(
                        text = Strings[StringKey.MCP_TOKENS_TAKEOVER],
                        onClick = { onIntent(ApiTokensIntent.ConfirmTakeover) },
                        busy = state.busy,
                        modifier = Modifier.weight(1f),
                    )
                    AppButton(
                        text = Strings[StringKey.COMMON_CANCEL],
                        onClick = { onIntent(ApiTokensIntent.CancelTakeover) },
                        variant = AppButtonVariant.SECONDARY,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                AppButton(
                    text = generateLabel(state.availability),
                    onClick = { onIntent(ApiTokensIntent.Generate) },
                    enabled = state.canGenerate,
                    busy = state.busy,
                    fillMaxWidth = true,
                )
            }
        }
        state.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }
    }
}

@Composable
@ReadOnlyComposable
private fun generateLabel(availability: WorkstationAvailability?): String = when (availability) {
    WorkstationAvailability.MINE -> Strings[StringKey.MCP_TOKENS_REGENERATE]
    WorkstationAvailability.OTHER -> Strings[StringKey.MCP_TOKENS_TAKEOVER]
    else -> Strings[StringKey.MCP_TOKENS_GENERATE]
}

@Composable
@ReadOnlyComposable
private fun availabilityLabel(state: ApiTokensState): String? {
    if (state.workstation.isBlank()) return null
    return when (state.availability) {
        WorkstationAvailability.FREE -> Strings[StringKey.MCP_TOKENS_AVAIL_FREE]
        WorkstationAvailability.MINE -> Strings[StringKey.MCP_TOKENS_AVAIL_MINE]
        WorkstationAvailability.OTHER -> Strings[StringKey.MCP_TOKENS_AVAIL_OTHER]
        null -> null
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
            AppText(token.workstationName, AppTextStyle.BODY_STRONG)
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
