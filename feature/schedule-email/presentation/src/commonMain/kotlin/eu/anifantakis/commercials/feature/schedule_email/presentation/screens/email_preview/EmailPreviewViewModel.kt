package eu.anifantakis.commercials.feature.schedule_email.presentation.screens.email_preview

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailPreviewRequest
import eu.anifantakis.commercials.feature.schedule_email.domain.ScheduleEmailRepository
import eu.anifantakis.commercials.feature.schedule_email.presentation.screens.send_schedule_email.toDisplayMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class EmailPreviewState(
    val html: String? = null,
    val loading: Boolean = true,
    val sending: Boolean = false,
    val error: String? = null,
)

sealed interface EmailPreviewIntent {
    data object Send : EmailPreviewIntent
}

sealed interface EmailPreviewEffect {
    /** The server confirmed the send; [status] is its status line. */
    data class Sent(val status: String) : EmailPreviewEffect
}

/**
 * The preview SCREEN's own ViewModel (per-screen ViewModels; the main
 * dialog's ViewModel only assembles the [request]). Loads the exact HTML
 * the customer will receive, and owns the real send.
 */
@Stable
class EmailPreviewViewModel(
    private val request: EmailPreviewRequest,
    private val repository: ScheduleEmailRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(EmailPreviewState())
    val state by _state
        .onStart { loadPreview() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), _state.value)
        .toComposeState(viewModelScope)

    private val eventChannel = Channel<EmailPreviewEffect>()
    val events = eventChannel.receiveAsFlow()

    fun onAction(intent: EmailPreviewIntent) {
        when (intent) {
            EmailPreviewIntent.Send -> send()
        }
    }

    private fun loadPreview() {
        viewModelScope.launch {
            when (val result = repository.previewHtml(request)) {
                is DataResult.Success -> _state.update { it.copy(html = result.data, loading = false) }
                is DataResult.Failure -> _state.update {
                    it.copy(error = result.error.toDisplayMessage(), loading = false)
                }
            }
        }
    }

    private fun send() {
        if (_state.value.sending) return
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.send(request)) {
                is DataResult.Success -> eventChannel.send(EmailPreviewEffect.Sent(result.data))
                is DataResult.Failure -> _state.update {
                    it.copy(sending = false, error = result.error.toDisplayMessage())
                }
            }
        }
    }
}
