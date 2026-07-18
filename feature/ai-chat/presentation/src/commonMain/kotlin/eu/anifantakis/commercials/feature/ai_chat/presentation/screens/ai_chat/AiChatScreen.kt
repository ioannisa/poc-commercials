package eu.anifantakis.commercials.feature.ai_chat.presentation.screens.ai_chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import eu.anifantakis.commercials.core.domain.auth.AiChatProviderOption
import eu.anifantakis.commercials.core.domain.auth.UserSession
import eu.anifantakis.commercials.core.domain.context.ActiveScreenContext
import eu.anifantakis.commercials.core.domain.refresh.DataRefreshBus
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCard
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconButton
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
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatConversation
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatHistoryStore
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatMessage
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatPreferences
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatRepository
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatRole
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatStoredTurn
import eu.anifantakis.commercials.feature.ai_chat.domain.AiClientAction
import eu.anifantakis.commercials.feature.ai_chat.domain.AiProposal
import eu.anifantakis.commercials.feature.ai_chat.domain.AiToolStep
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Instant

/** A confirmation card's lifecycle after the user acted on it. */
enum class AiActionStatus { RUNNING, EXECUTED, FAILED, DECLINED }

@Immutable
data class AiActionState(val status: AiActionStatus, val detail: UiText? = null)

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
    /** Per-card execution state, keyed by [AiProposal.id]; absent = still pending. */
    val actionResults: ImmutableMap<String, AiActionState> = persistentMapOf(),
    /** Saved conversations (newest first) - shown when [showHistory] is on. */
    val history: ImmutableList<AiChatConversation> = persistentListOf(),
    val showHistory: Boolean = false,
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

    /** The user pressed a confirmation card's approve button. */
    data class ConfirmAction(val proposal: AiProposal) : AiChatIntent

    /** The user dismissed a confirmation card. */
    data class DeclineAction(val proposal: AiProposal) : AiChatIntent

    data object ToggleHistory : AiChatIntent
    data class RestoreConversation(val id: String) : AiChatIntent
    data class DeleteConversation(val id: String) : AiChatIntent
}

@Stable
class AiChatViewModel(
    private val repository: AiChatRepository,
    private val prefs: AiChatPreferences,
    private val refreshBus: DataRefreshBus,
    private val session: UserSession,
    private val historyStore: AiChatHistoryStore,
    private val screenContext: ActiveScreenContext,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(AiChatState())
    val state by _state.toComposeState(viewModelScope)

    /** The live conversation's history id; null until its first message. */
    private var conversationId: String? = null

    fun onAction(intent: AiChatIntent) {
        when (intent) {
            is AiChatIntent.Init -> init(intent.providers)
            is AiChatIntent.SelectProvider -> selectProvider(intent.id)
            is AiChatIntent.SelectModel -> selectModel(intent.model)
            is AiChatIntent.InputChanged -> _state.update { it.copy(input = intent.value, error = null) }
            AiChatIntent.Send -> send()
            AiChatIntent.Clear -> {
                conversationId = null
                _state.update {
                    it.copy(messages = persistentListOf(), input = "", error = null, actionResults = persistentMapOf())
                }
            }
            is AiChatIntent.ConfirmAction -> confirm(intent.proposal)
            is AiChatIntent.DeclineAction -> decline(intent.proposal)
            AiChatIntent.ToggleHistory -> _state.update {
                it.copy(showHistory = !it.showHistory, history = historyStore.load().toImmutableList())
            }
            is AiChatIntent.RestoreConversation -> restore(intent.id)
            is AiChatIntent.DeleteConversation -> _state.update {
                historyStore.delete(intent.id)
                it.copy(history = historyStore.load().toImmutableList())
            }
        }
    }

    /**
     * Adopts the session's catalog. The keep-alive can change it mid-session
     * (server.yaml edit), so a still-valid selection survives; before the
     * first selection, the SILENTLY PERSISTED provider/model preference is
     * tried (validated against the catalog - a stale entry from an edited
     * server.yaml falls through); anything else lands on the default: first
     * provider, its first model.
     */
    private fun init(providers: List<AiChatProviderOption>) {
        _state.update { s ->
            if (providers == s.providers) return
            val wantedProvider = s.selectedProviderId ?: prefs.provider.takeIf { it.isNotBlank() }
            val provider = providers.firstOrNull { it.id == wantedProvider } ?: providers.firstOrNull()
            val wantedModel = s.selectedModel ?: prefs.model.takeIf { it.isNotBlank() }
            val model = provider?.models?.firstOrNull { it == wantedModel } ?: provider?.models?.firstOrNull()
            s.copy(
                providers = providers.toImmutableList(),
                selectedProviderId = provider?.id,
                selectedModel = model,
            )
        }
    }

    /**
     * Switching provider resets the model to THAT provider's default (first).
     * Both picks persist silently - the next session restores them.
     */
    private fun selectProvider(id: String) {
        val s = _state.value
        val provider = s.providers.firstOrNull { it.id == id } ?: return
        if (provider.id == s.selectedProviderId) return
        val model = provider.models.firstOrNull()
        _state.update { it.copy(selectedProviderId = provider.id, selectedModel = model) }
        prefs.provider = provider.id
        prefs.model = model.orEmpty()
    }

    private fun selectModel(model: String) {
        if (_state.value.selectedProvider?.models?.contains(model) != true) return
        _state.update { it.copy(selectedModel = model) }
        prefs.model = model
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
            when (val result = repository.send(history, provider, model, screenContext.current)) {
                is DataResult.Success -> {
                    _state.update {
                        it.copy(
                            messages = (it.messages + AiChatMessage(
                                role = AiChatRole.ASSISTANT,
                                text = result.data.text,
                                steps = result.data.steps,
                                proposals = result.data.proposals,
                            )).toImmutableList(),
                            busy = false,
                        )
                    }
                    result.data.clientActions.forEach(::handleClientAction)
                    autosave()
                }
                is DataResult.Failure -> {
                    _state.update { it.copy(busy = false, error = result.error.toUiText()) }
                    autosave()
                }
            }
        }
    }

    /**
     * AUTOSAVE the live conversation - called on every appended turn, so
     * there is no save button to forget. The transcript (incl. NOTE turns,
     * which carry each card's outcome) is what persists; live cards are not.
     */
    private fun autosave() {
        val messages = _state.value.messages
        if (messages.isEmpty()) return
        val id = conversationId ?: newConversationId().also { conversationId = it }
        val title = messages.firstOrNull { it.role == AiChatRole.USER }?.text?.take(48) ?: "…"
        historyStore.upsert(
            AiChatConversation(
                id = id,
                title = title,
                updatedAtEpochMs = nowEpochMs(),
                turns = messages.map { m ->
                    AiChatStoredTurn(role = m.role.name, text = m.text, tools = m.steps.map { it.tool })
                },
            )
        )
    }

    private fun restore(id: String) {
        val conversation = historyStore.load().firstOrNull { it.id == id } ?: return
        conversationId = conversation.id
        _state.update {
            it.copy(
                messages = conversation.turns.map { t ->
                    AiChatMessage(
                        role = runCatching { AiChatRole.valueOf(t.role) }.getOrDefault(AiChatRole.ASSISTANT),
                        text = t.text,
                        steps = t.tools.map { tool -> AiToolStep(tool, isError = false) },
                    )
                }.toImmutableList(),
                input = "",
                error = null,
                actionResults = persistentMapOf(),
                showHistory = false,
            )
        }
    }

    private fun newConversationId(): String =
        "${nowEpochMs()}-${kotlin.random.Random.nextLong().toULong().toString(16)}"

    private fun nowEpochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    /**
     * APPROVE: replay the prepared call server-side (confirm=true there). The
     * outcome lands on the card AND as a NOTE turn, so the model learns what
     * actually happened on the next request; a successful write also pokes
     * the [DataRefreshBus] - the timetable refetches and the user watches
     * the change land live.
     */
    private fun confirm(p: AiProposal) {
        if (_state.value.actionResults.containsKey(p.id)) return   // one shot per card
        setAction(p.id, AiActionState(AiActionStatus.RUNNING))
        viewModelScope.launch {
            when (val result = repository.execute(p.tool, p.argumentsJson)) {
                is DataResult.Success -> {
                    val out = result.data
                    if (out.isError) {
                        setAction(p.id, AiActionState(AiActionStatus.FAILED, UiText.Dynamic(out.text)))
                        appendNote(
                            StringKey.AI_CHAT_NOTE_FAILED.localized().withArgs(listOf(p.tool, out.text.take(500)))
                        )
                    } else {
                        setAction(p.id, AiActionState(AiActionStatus.EXECUTED))
                        appendNote(
                            StringKey.AI_CHAT_NOTE_EXECUTED.localized().withArgs(listOf(p.tool, out.text.take(500)))
                        )
                        refreshBus.notifyChanged()
                    }
                }
                is DataResult.Failure -> {
                    // Transport-level failure: the card can be retried, so no
                    // note yet - nothing definitive happened.
                    _state.update {
                        it.copy(
                            actionResults = (it.actionResults - p.id).toImmutableMap(),
                            error = result.error.toUiText(),
                        )
                    }
                }
            }
        }
    }

    private fun decline(p: AiProposal) {
        if (_state.value.actionResults.containsKey(p.id)) return
        setAction(p.id, AiActionState(AiActionStatus.DECLINED))
        appendNote(StringKey.AI_CHAT_NOTE_DECLINED.localized().withArgs(listOf(p.tool)))
    }

    /**
     * UI actions the assistant asked the APP to perform. Grant-checked again
     * here (the session's own station list is the source of truth); unknown
     * actions are ignored - an older client simply does nothing with a newer
     * server's actions. The station switch bumps the session revision, so the
     * grid reloads and the chat's station pin follows on the next request.
     */
    private fun handleClientAction(action: AiClientAction) {
        when (action.action) {
            "switch_station" -> {
                val station = session.stations.firstOrNull { it.id == action.station } ?: return
                if (session.selectedStation?.id != station.id) {
                    session.selectStation(station.id)
                    appendNote(StringKey.AI_CHAT_NOTE_SWITCHED.localized().withArgs(listOf(station.name)))
                }
            }
        }
    }

    private fun setAction(id: String, action: AiActionState) {
        _state.update { it.copy(actionResults = (it.actionResults + (id to action)).toImmutableMap()) }
    }

    private fun appendNote(text: String) {
        _state.update {
            it.copy(messages = (it.messages + AiChatMessage(AiChatRole.NOTE, text)).toImmutableList())
        }
        autosave()
    }
}

/**
 * The in-app AI assistant: ask about schedules, breaks, spots, contracts and
 * customers in natural language. Phase 1 is READ-ONLY - the server exposes
 * only the read tools to the model. Rendered as a COMPANION PANEL docked
 * beside the running screen (the sparkles toolbar button and the Preferences
 * entry toggle it); [providers] is the session's catalog and drives the
 * provider/model dropdowns.
 */
@Composable
fun AiChatScreenRoot(
    providers: () -> List<AiChatProviderOption>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AiChatViewModel = koinViewModel(),
) {
    val catalog = providers()
    LaunchedEffect(catalog) { viewModel.onAction(AiChatIntent.Init(catalog)) }
    AiChatScreen(state = viewModel.state, onIntent = viewModel::onAction, onClose = onClose, modifier = modifier)
}

@Composable
private fun AiChatScreen(
    state: AiChatState,
    onIntent: (AiChatIntent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Keep the newest turn in view as the conversation grows.
    LaunchedEffect(state.messages.size, state.busy) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier.padding(UIConst.paddingRegular)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
            AppText(Strings[StringKey.AI_CHAT_TITLE], AppTextStyle.SECTION_TITLE, modifier = Modifier.weight(1f))
            AppIconButton(
                label = Strings[StringKey.AI_CHAT_HISTORY],
                icon = AppDrawableRepo.history,
                onClick = { onIntent(AiChatIntent.ToggleHistory) },
            )
            AppButton(
                text = Strings[StringKey.AI_CHAT_CLEAR],
                onClick = { onIntent(AiChatIntent.Clear) },
                variant = AppButtonVariant.TEXT,
                enabled = !state.busy && state.messages.isNotEmpty(),
            )
            AppIconButton(
                label = Strings[StringKey.COMMON_CLOSE],
                icon = AppDrawableRepo.close,
                onClick = onClose,
            )
        }

        // Which brain answers: provider + that provider's models. A lone option
        // renders as plain text - nothing else to pick, so no dropdown at all.
        // Two stacked rows: side by side they overflow the panel's width.
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
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
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

        if (state.showHistory) {
            HistoryList(
                history = state.history,
                onIntent = onIntent,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = UIConst.paddingSmall),
            )
        } else LazyColumn(
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
                ChatBubble(message, state.actionResults, onIntent)
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
 * Saved conversations, newest first: click restores (the live conversation is
 * already autosaved), the trash icon deletes. Titles are the first user
 * message of each conversation.
 */
@Composable
private fun HistoryList(
    history: ImmutableList<AiChatConversation>,
    onIntent: (AiChatIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
        if (history.isEmpty()) {
            item { AppText(Strings[StringKey.AI_CHAT_HISTORY_EMPTY], AppTextStyle.NOTE) }
        }
        items(history, key = { it.id }) { conversation ->
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(UIConst.paddingSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { onIntent(AiChatIntent.RestoreConversation(conversation.id)) },
                    ) {
                        AppText(conversation.title, AppTextStyle.BODY_STRONG, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AppText(formatConversationStamp(conversation.updatedAtEpochMs), AppTextStyle.TINY)
                    }
                    AppIconButton(
                        label = Strings[StringKey.COMMON_DELETE],
                        icon = AppDrawableRepo.delete,
                        onClick = { onIntent(AiChatIntent.DeleteConversation(conversation.id)) },
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** dd/MM HH:mm in the device's zone - enough to tell conversations apart. */
private fun formatConversationStamp(epochMs: Long): String {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${p(dt.day)}/${p(dt.month.number)} ${p(dt.hour)}:${p(dt.minute)}"
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

/**
 * Markdown typography matched to the design system: body text at the SAME
 * size as [AppTextStyle.BODY] (the renderer's default bodyLarge reads a step
 * bigger than the user's own bubbles), code in the design system's mono face,
 * headings tamed for a narrow panel, tables at TABLE_CELL size. Built from
 * [AppTheme.typography], which the app-wide font-size preference already
 * scales - so the chat resizes with every other screen.
 */
@Composable
private fun chatMarkdownTypography(): MarkdownTypography {
    val t = AppTheme.typography
    val m = t.material
    val body = m.bodyMedium
    return markdownTypography(
        h1 = t.sectionTitle,
        h2 = m.titleMedium,
        h3 = m.titleMedium,
        h4 = m.titleSmall,
        h5 = m.titleSmall,
        h6 = m.titleSmall,
        text = body,
        code = t.mono,
        inlineCode = t.mono,
        quote = body.copy(fontStyle = FontStyle.Italic),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        textLink = TextLinkStyles(
            style = body.copy(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline).toSpanStyle()
        ),
        table = m.bodySmall,
    )
}

/** Brand nouns, identical in every language - not localization material. */
private fun providerDisplayName(id: String): String = when (id) {
    "anthropic" -> "Anthropic"
    "openai" -> "OpenAI"
    "gemini" -> "Gemini"
    else -> id
}

/**
 * User turns hug the end edge, assistant turns the start edge (auto-mirrors in
 * RTL); NOTE turns (action approved/declined annotations) sit centred and
 * small. Assistant turns render as MARKDOWN (models emit it regardless of
 * prompting - GFM tables included); the user's own words stay plain text.
 * Everything sits in a [SelectionContainer], so answers can be selected and
 * copied (one container per bubble: selection never straddles messages).
 * Assistant turns may carry [AiProposal] confirmation cards under the text.
 */
@Composable
private fun ChatBubble(
    message: AiChatMessage,
    actionResults: ImmutableMap<String, AiActionState>,
    onIntent: (AiChatIntent) -> Unit,
) {
    if (message.role == AiChatRole.NOTE) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AppText(message.text, AppTextStyle.TINY)
        }
        return
    }
    val isUser = message.role == AiChatRole.USER
    Box(Modifier.fillMaxWidth()) {
        AppCard(
            modifier = Modifier
                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(UIConst.paddingSmall)) {
                SelectionContainer {
                    Column {
                        if (isUser) {
                            AppText(message.text, AppTextStyle.BODY)
                        } else {
                            Markdown(message.text, typography = chatMarkdownTypography())
                        }
                    }
                }
                message.proposals.forEach { proposal ->
                    ProposalCard(proposal, actionResults[proposal.id], onIntent)
                }
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

/**
 * One prepared mutation: the tool, its validated dry-run preview, and the
 * approve/cancel pair. NOTHING executes without the approve press; the card
 * then shows its terminal state (executed / failed / cancelled) and the
 * buttons are gone - a card is one-shot.
 */
@Composable
private fun ProposalCard(
    proposal: AiProposal,
    action: AiActionState?,
    onIntent: (AiChatIntent) -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(top = UIConst.paddingSmall)) {
        Column(Modifier.padding(UIConst.paddingSmall)) {
            AppText(Strings[StringKey.AI_CHAT_ACTION_TITLE], AppTextStyle.BODY_STRONG)
            AppText(proposal.tool, AppTextStyle.TINY)
            SelectionContainer {
                AppText(proposal.preview, AppTextStyle.LOG_LINE)
            }
            when (action?.status) {
                null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
                ) {
                    AppButton(
                        text = Strings[StringKey.AI_CHAT_APPROVE],
                        onClick = { onIntent(AiChatIntent.ConfirmAction(proposal)) },
                        // Destruction reads as destruction, even inside a card.
                        variant = if (proposal.tool.startsWith("delete")) {
                            AppButtonVariant.DESTRUCTIVE
                        } else {
                            AppButtonVariant.PRIMARY
                        },
                    )
                    AppButton(
                        text = Strings[StringKey.COMMON_CANCEL],
                        onClick = { onIntent(AiChatIntent.DeclineAction(proposal)) },
                        variant = AppButtonVariant.TEXT,
                    )
                }
                AiActionStatus.RUNNING -> AppButton(
                    text = Strings[StringKey.AI_CHAT_APPROVE],
                    onClick = {},
                    enabled = false,
                    busy = true,
                )
                AiActionStatus.EXECUTED -> AppText(
                    "✓ " + Strings[StringKey.AI_CHAT_ACTION_DONE],
                    AppTextStyle.BODY_STRONG,
                    color = MaterialTheme.colorScheme.primary,
                )
                AiActionStatus.FAILED -> AppText(
                    Strings[StringKey.AI_CHAT_ACTION_FAILED] +
                        (action.detail?.let { ": " + it.asString() } ?: ""),
                    AppTextStyle.ERROR_NOTE,
                )
                AiActionStatus.DECLINED -> AppText(
                    Strings[StringKey.AI_CHAT_ACTION_DECLINED],
                    AppTextStyle.NOTE,
                )
            }
        }
    }
}
