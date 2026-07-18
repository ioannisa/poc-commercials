package eu.anifantakis.commercials.feature.ai_chat.presentation.screens.ai_chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.auth.AiChatProviderOption
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCard
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconSize
import eu.anifantakis.commercials.core.presentation.design_system.components.AppLoadingIndicator
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatMessage
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatRepository
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatRole
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Immutable
data class AiChatState(
    val messages: ImmutableList<AiChatMessage> = persistentListOf(),
    val input: String = "",
    /** A request is in flight - the input locks and the typing bubble shows. */
    val busy: Boolean = false,
    val error: UiText? = null,
    /** The server's provider catalog (session-supplied), default first. */
    val providers: ImmutableList<AiChatProviderOption> = persistentListOf(),
    val selectedProviderId: String? = null,
    val selectedModel: String? = null,
) {
    val selectedProvider: AiChatProviderOption? get() = providers.firstOrNull { it.id == selectedProviderId }
    val canSend: Boolean get() = !busy && input.isNotBlank() && selectedProviderId != null && selectedModel != null
}

sealed interface AiChatIntent {
    /** The session's provider catalog, (re)supplied by the navigation entry. */
    data class Init(val providers: List<AiChatProviderOption>) : AiChatIntent
    data class SelectProvider(val id: String) : AiChatIntent
    data class SelectModel(val model: String) : AiChatIntent
    data class InputChanged(val value: String) : AiChatIntent
    data object Send : AiChatIntent
    data object Clear : AiChatIntent
}

@Stable
class AiChatViewModel(
    private val repository: AiChatRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(AiChatState())
    val state by _state.toComposeState(viewModelScope)

    fun onAction(intent: AiChatIntent) {
        when (intent) {
            is AiChatIntent.Init -> init(intent.providers)
            is AiChatIntent.SelectProvider -> selectProvider(intent.id)
            is AiChatIntent.SelectModel -> selectModel(intent.model)
            is AiChatIntent.InputChanged -> _state.update { it.copy(input = intent.value, error = null) }
            AiChatIntent.Send -> send()
            AiChatIntent.Clear -> _state.update { it.copy(messages = persistentListOf(), input = "", error = null) }
        }
    }

    /**
     * Adopts the session's catalog. The keep-alive can change it mid-session
     * (server.yaml edit), so a still-valid selection survives and anything
     * else falls back to the default: first provider, its first model.
     */
    private fun init(providers: List<AiChatProviderOption>) {
        _state.update { s ->
            if (providers == s.providers) return
            val provider = providers.firstOrNull { it.id == s.selectedProviderId } ?: providers.firstOrNull()
            val model = provider?.models?.firstOrNull { it == s.selectedModel } ?: provider?.models?.firstOrNull()
            s.copy(
                providers = providers.toImmutableList(),
                selectedProviderId = provider?.id,
                selectedModel = model,
            )
        }
    }

    /** Switching provider resets the model to THAT provider's default (first). */
    private fun selectProvider(id: String) {
        _state.update { s ->
            val provider = s.providers.firstOrNull { it.id == id } ?: return
            if (provider.id == s.selectedProviderId) return
            s.copy(selectedProviderId = provider.id, selectedModel = provider.models.firstOrNull())
        }
    }

    private fun selectModel(model: String) {
        _state.update { s ->
            if (s.selectedProvider?.models?.contains(model) != true) return
            s.copy(selectedModel = model)
        }
    }

    private fun send() {
        val s = _state.value
        val text = s.input.trim()
        val provider = s.selectedProviderId ?: return
        val model = s.selectedModel ?: return
        if (text.isBlank() || s.busy) return
        val history = (s.messages + AiChatMessage(AiChatRole.USER, text)).toImmutableList()
        _state.update { it.copy(messages = history, input = "", busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.send(history, provider, model)) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        messages = (it.messages + AiChatMessage(
                            role = AiChatRole.ASSISTANT,
                            text = result.data.text,
                            steps = result.data.steps,
                        )).toImmutableList(),
                        busy = false,
                    )
                }
                is DataResult.Failure -> _state.update {
                    it.copy(busy = false, error = result.error.toUiText())
                }
            }
        }
    }
}

/**
 * The in-app AI assistant: ask about schedules, breaks, spots, contracts and
 * customers in natural language. Phase 1 is READ-ONLY - the server exposes
 * only the read tools to the model. Reached from Preferences when the server
 * configures at least one `ai:` provider; [providers] is the session's
 * catalog and drives the provider/model dropdowns.
 */
@Composable
fun AiChatScreenRoot(
    providers: () -> List<AiChatProviderOption>,
    onBack: () -> Unit,
    viewModel: AiChatViewModel = koinViewModel(),
) {
    val catalog = providers()
    LaunchedEffect(catalog) { viewModel.onAction(AiChatIntent.Init(catalog)) }
    AiChatScreen(state = viewModel.state, onIntent = viewModel::onAction, onBack = onBack)
}

@Composable
private fun AiChatScreen(
    state: AiChatState,
    onIntent: (AiChatIntent) -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Keep the newest turn in view as the conversation grows.
    LaunchedEffect(state.messages.size, state.busy) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().padding(UIConst.paddingRegular)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
            AppText(Strings[StringKey.AI_CHAT_TITLE], AppTextStyle.SECTION_TITLE, modifier = Modifier.weight(1f))
            AppButton(
                text = Strings[StringKey.AI_CHAT_CLEAR],
                onClick = { onIntent(AiChatIntent.Clear) },
                variant = AppButtonVariant.TEXT,
                enabled = !state.busy && state.messages.isNotEmpty(),
            )
            AppButton(
                text = Strings[StringKey.COMMON_CLOSE],
                onClick = onBack,
                variant = AppButtonVariant.SECONDARY,
            )
        }

        // Which brain answers: provider + that provider's models. A lone option
        // renders as plain text - nothing else to pick, so no dropdown at all.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
            AppText(Strings[StringKey.AI_CHAT_PROVIDER], AppTextStyle.NOTE)
            ChatDropdown(
                options = state.providers.map { it.id }.toImmutableList(),
                selected = state.selectedProviderId,
                display = ::providerDisplayName,
                contentDescription = Strings[StringKey.AI_CHAT_PROVIDER],
                enabled = !state.busy,
                onSelect = { onIntent(AiChatIntent.SelectProvider(it)) },
            )
            AppText(Strings[StringKey.AI_CHAT_MODEL], AppTextStyle.NOTE)
            ChatDropdown(
                options = state.selectedProvider?.models.orEmpty().toImmutableList(),
                selected = state.selectedModel,
                display = { it },
                contentDescription = Strings[StringKey.AI_CHAT_MODEL],
                enabled = !state.busy,
                onSelect = { onIntent(AiChatIntent.SelectModel(it)) },
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = UIConst.paddingSmall),
            verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
            if (state.messages.isEmpty() && !state.busy) {
                item {
                    AppText(Strings[StringKey.AI_CHAT_EMPTY], AppTextStyle.NOTE)
                }
            }
            items(state.messages) { message ->
                ChatBubble(message)
            }
            if (state.busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppLoadingIndicator(isLoading = true)
                        AppText(
                            Strings[StringKey.AI_CHAT_THINKING],
                            AppTextStyle.NOTE,
                            modifier = Modifier.padding(start = UIConst.paddingSmall),
                        )
                    }
                }
            }
        }

        state.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
            AppTextField(
                value = state.input,
                onValueChange = { onIntent(AiChatIntent.InputChanged(it)) },
                label = Strings[StringKey.AI_CHAT_INPUT_HINT],
                enabled = !state.busy,
                modifier = Modifier
                    .weight(1f)
                    // ENTER sends without reaching for the mouse; Shift+ENTER
                    // keeps inserting a newline for multi-line questions.
                    .onPreviewKeyEvent { event ->
                        val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (isEnter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                            if (state.canSend) onIntent(AiChatIntent.Send)
                            true   // swallow even when not sendable - no stray newline
                        } else {
                            false
                        }
                    },
            )
            AppButton(
                text = Strings[StringKey.AI_CHAT_SEND],
                onClick = { onIntent(AiChatIntent.Send) },
                enabled = state.canSend,
                busy = state.busy,
            )
        }
    }
}

/**
 * One pick of the "which brain" pair (provider, then model), mirroring the
 * Timetable station selector: >1 options = a text-button dropdown, exactly 1 =
 * a plain preselected label with NO expansion (there is nothing else to pick).
 */
@Composable
private fun ChatDropdown(
    options: ImmutableList<String>,
    selected: String?,
    display: (String) -> String,
    contentDescription: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    if (selected == null) return
    var expanded by remember { mutableStateOf(false) }

    Box {
        if (options.size > 1) {
            AppButton(onClick = { expanded = true }, variant = AppButtonVariant.TEXT, enabled = enabled) {
                AppText(display(selected), AppTextStyle.BUTTON, color = MaterialTheme.colorScheme.primary)
                AppIcon(
                    AppDrawableRepo.arrowDropDown,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { AppText(display(option), AppTextStyle.BUTTON) },
                        leadingIcon = {
                            if (option == selected) {
                                AppIcon(AppDrawableRepo.check, size = AppIconSize.SMALL)
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        } else {
            AppText(
                display(selected),
                AppTextStyle.BUTTON,
                modifier = Modifier.padding(horizontal = UIConst.paddingSmall),
            )
        }
    }
}

/** Brand nouns, identical in every language - not localization material. */
private fun providerDisplayName(id: String): String = when (id) {
    "anthropic" -> "Anthropic"
    "openai" -> "OpenAI"
    "gemini" -> "Gemini"
    else -> id
}

/** User turns hug the end edge, assistant turns the start edge (auto-mirrors in RTL). */
@Composable
private fun ChatBubble(message: AiChatMessage) {
    val isUser = message.role == AiChatRole.USER
    Box(Modifier.fillMaxWidth()) {
        AppCard(
            modifier = Modifier
                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(UIConst.paddingSmall)) {
                AppText(message.text, AppTextStyle.BODY)
                if (message.steps.isNotEmpty()) {
                    AppText(
                        Strings[StringKey.AI_CHAT_TOOLS_USED].withArgs(
                            listOf(message.steps.joinToString(", ") { it.tool })
                        ),
                        AppTextStyle.TINY,
                    )
                }
            }
        }
    }
}
