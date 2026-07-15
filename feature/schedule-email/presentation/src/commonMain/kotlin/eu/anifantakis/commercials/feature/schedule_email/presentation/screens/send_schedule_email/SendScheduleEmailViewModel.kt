package eu.anifantakis.commercials.feature.schedule_email.presentation.screens.send_schedule_email

import eu.anifantakis.commercials.core.presentation.helper.UiText
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.party_search.PartySearchRepository
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailActivityMonth
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailError
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailLogEntry
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailPreviewRequest
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailSpot
import eu.anifantakis.commercials.feature.schedule_email.domain.ScheduleEmailRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class SendScheduleEmailState(
    val kind: PartyKind = PartyKind.CUSTOMER,
    val query: String = "",
    val results: ImmutableList<Party> = persistentListOf(),
    val searching: Boolean = false,
    val selectedParty: Party? = null,
    /** The kind the selection was made under - toggling radios later must not reinterpret it. */
    val selectedKind: PartyKind = PartyKind.CUSTOMER,
    val activity: ImmutableList<EmailActivityMonth> = persistentListOf(),
    val loadingActivity: Boolean = false,
    val selectedYear: Int? = null,
    val selectedMonth: Int? = null,
    val spots: ImmutableList<EmailSpot> = persistentListOf(),
    val includedSpotIds: ImmutableSet<Long> = persistentSetOf(),
    /** What the operator typed / the smart pre-fill (the last-sent address). Empty
     *  falls back to [customerEmail]. */
    val recipient: String = "",
    /** The customer's stored default - the FAINT PLACEHOLDER shown when [recipient]
     *  is empty. Empty when the customer has no real email (then a recipient is
     *  mandatory). Placeholders (`@example.`) are stripped before it reaches here. */
    val customerEmail: String = "",
    val note: String = "",
    val history: ImmutableList<EmailLogEntry> = persistentListOf(),
    val error: UiText? = null,
    /** The server's status line after a successful send. */
    val done: String? = null,
) {
    val chosenSpotIds: List<Long> get() = spots.map { it.spotId }.filter { it in includedSpotIds }

    /** What the email actually goes to: the field if filled, else the placeholder. */
    val effectiveRecipient: String get() = recipient.ifBlank { customerEmail }

    val canPreview: Boolean
        get() = selectedParty != null && selectedYear != null && selectedMonth != null &&
            effectiveRecipient.isNotBlank() && chosenSpotIds.isNotEmpty()
}

sealed interface SendScheduleEmailIntent {
    data class KindChanged(val kind: PartyKind) : SendScheduleEmailIntent
    data class QueryChanged(val query: String) : SendScheduleEmailIntent
    data class PartySelected(val party: Party) : SendScheduleEmailIntent
    data class YearSelected(val year: Int) : SendScheduleEmailIntent
    data class MonthSelected(val year: Int, val month: Int) : SendScheduleEmailIntent
    data class SpotToggled(val spotId: Long) : SendScheduleEmailIntent
    data class RecipientChanged(val value: String) : SendScheduleEmailIntent
    data class NoteChanged(val value: String) : SendScheduleEmailIntent
    data object RequestPreview : SendScheduleEmailIntent

    /** Reported by the preview screen's own ViewModel after a real send. */
    data class MarkSent(val status: String) : SendScheduleEmailIntent

    /** Clears everything for a fresh dialog. Fired when the dialog (re)opens - the
     *  ViewModel outlives the dialog, so without this a reopened dialog would still
     *  show the previous send's "sent" confirmation instead of an empty form. */
    data object Reset : SendScheduleEmailIntent
}

sealed interface SendScheduleEmailEffect {
    data class OpenPreview(val request: EmailPreviewRequest) : SendScheduleEmailEffect
}

/**
 * The main send-dialog screen: party search (core PartySearchRepository,
 * 600ms/3+ chars debounce in here), year->month drill-down, the month's
 * spots and the recipient/note. Preview/send belong to the preview screen's
 * OWN ViewModel; this one only assembles the [EmailPreviewRequest].
 */
@Stable
class SendScheduleEmailViewModel(
    private val repository: ScheduleEmailRepository,
    private val partySearch: PartySearchRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(SendScheduleEmailState())
    val state by _state.toComposeState(viewModelScope)

    private val eventChannel = Channel<SendScheduleEmailEffect>()
    val events = eventChannel.receiveAsFlow()

    private var searchJob: Job? = null

    fun onAction(intent: SendScheduleEmailIntent) {
        when (intent) {
            is SendScheduleEmailIntent.KindChanged -> {
                if (_state.value.kind != intent.kind) {
                    _state.update { it.copy(kind = intent.kind, results = persistentListOf()) }
                    debouncedSearch()
                }
            }

            is SendScheduleEmailIntent.QueryChanged -> {
                _state.update { it.copy(query = intent.query) }
                debouncedSearch()
            }

            is SendScheduleEmailIntent.PartySelected -> selectParty(intent.party)
            is SendScheduleEmailIntent.YearSelected -> _state.update {
                it.copy(
                    selectedYear = intent.year,
                    selectedMonth = null,
                    spots = persistentListOf(),
                    includedSpotIds = persistentSetOf(),
                )
            }

            is SendScheduleEmailIntent.MonthSelected -> selectMonth(intent.year, intent.month)

            is SendScheduleEmailIntent.SpotToggled -> _state.update {
                val included = if (intent.spotId in it.includedSpotIds) {
                    (it.includedSpotIds - intent.spotId)
                } else {
                    (it.includedSpotIds + intent.spotId)
                }
                it.copy(includedSpotIds = included.toImmutableSet())
            }

            is SendScheduleEmailIntent.RecipientChanged -> _state.update { it.copy(recipient = intent.value) }
            is SendScheduleEmailIntent.NoteChanged -> _state.update { it.copy(note = intent.value) }

            SendScheduleEmailIntent.RequestPreview -> viewModelScope.launch {
                val s = _state.value
                if (!s.canPreview) return@launch
                eventChannel.send(
                    SendScheduleEmailEffect.OpenPreview(
                        EmailPreviewRequest(
                            year = s.selectedYear!!,
                            month = s.selectedMonth!!,
                            clientCode = s.selectedParty!!.code,
                            kind = s.selectedKind,
                            spotIds = s.chosenSpotIds,
                            recipient = s.effectiveRecipient,
                            personalMessage = s.note,
                        )
                    )
                )
            }

            is SendScheduleEmailIntent.MarkSent -> _state.update { it.copy(done = intent.status) }

            SendScheduleEmailIntent.Reset -> {
                searchJob?.cancel()
                _state.value = SendScheduleEmailState()
            }
        }
    }

    /** 600ms after the last keystroke, min 3 chars - the legacy contract. */
    private fun debouncedSearch() {
        searchJob?.cancel()
        val query = _state.value.query.trim()
        if (query.length < 3) {
            _state.update { it.copy(results = persistentListOf(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(600)
            _state.update { it.copy(searching = true) }
            when (val result = partySearch.search(query, _state.value.kind)) {
                is DataResult.Success -> _state.update {
                    it.copy(results = result.data.toImmutableList(), searching = false)
                }
                is DataResult.Failure -> _state.update {
                    it.copy(error = result.error.toUiText(), searching = false)
                }
            }
        }
    }

    private fun selectParty(party: Party) {
        _state.update {
            it.copy(
                selectedParty = party,
                selectedKind = it.kind,
                query = "",
                results = persistentListOf(),
                // The stored default becomes the faint PLACEHOLDER; the field starts
                // empty and is pre-filled below with the LAST-SENT address once the
                // history arrives (a customer whose accountant asked for a different
                // address last time gets that one offered again). Placeholders never
                // masquerade as a real default.
                recipient = "",
                customerEmail = realEmail(party.email),
                activity = persistentListOf(),
                loadingActivity = true,
                selectedYear = null,
                selectedMonth = null,
                spots = persistentListOf(),
                includedSpotIds = persistentSetOf(),
                history = persistentListOf(),
            )
        }
        viewModelScope.launch {
            when (val result = repository.activity(party.code, _state.value.selectedKind)) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        activity = result.data.toImmutableList(),
                        loadingActivity = false,
                        selectedYear = result.data.firstOrNull()?.year,
                    )
                }
                is DataResult.Failure -> _state.update {
                    it.copy(error = result.error.toUiText(), loadingActivity = false)
                }
            }
        }
        viewModelScope.launch {
            when (val result = repository.history(limit = 8, clientCode = party.code)) {
                is DataResult.Success -> _state.update { s ->
                    // Smart pre-fill: the MOST RECENT send's recipient (history is
                    // newest-first). Only while the field is still untouched, so it
                    // never clobbers what the operator has started typing.
                    val lastSent = realEmail(result.data.firstOrNull()?.recipient)
                    s.copy(
                        history = result.data.toImmutableList(),
                        recipient = if (s.recipient.isBlank() && lastSent.isNotBlank()) lastSent else s.recipient,
                    )
                }
                is DataResult.Failure -> Unit   // the audit list is best-effort
            }
        }
    }

    private fun selectMonth(year: Int, month: Int) {
        _state.update {
            it.copy(selectedMonth = month, spots = persistentListOf(), includedSpotIds = persistentSetOf())
        }
        val s = _state.value
        val party = s.selectedParty ?: return
        viewModelScope.launch {
            when (val result = repository.spots(year, month, party.code, s.selectedKind)) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        spots = result.data.toImmutableList(),
                        includedSpotIds = result.data.map { sp -> sp.spotId }.toImmutableSet(),
                    )
                }
                is DataResult.Failure -> _state.update { it.copy(error = result.error.toUiText()) }
            }
        }
    }
}

/** A real address, or "" for a blank or synthetic (`@example.`) placeholder - so a
 *  migration fake never reaches the recipient field as if it were genuine. */
private fun realEmail(v: String?): String =
    v?.trim()?.takeIf { it.isNotBlank() && !it.contains("@example.", ignoreCase = true) }.orEmpty()

/** Email failures to operator text; server messages pass through verbatim. */
fun EmailError.toUiText(): UiText = when (this) {
    is EmailError.Server -> UiText.Dynamic(message)
    is EmailError.Network -> error.toUiText()
}
