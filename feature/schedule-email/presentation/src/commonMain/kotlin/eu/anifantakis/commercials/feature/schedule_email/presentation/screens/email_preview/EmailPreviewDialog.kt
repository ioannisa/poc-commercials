package eu.anifantakis.commercials.feature.schedule_email.presentation.screens.email_preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.anifantakis.commercials.core.presentation.helper.ObserveEffects
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailPreviewRequest
import eu.anifantakis.commercials.feature.schedule_email.presentation.webview.EmailHtmlPreview
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Fullscreen-ish preview: the report exactly as the customer will receive
 * it, with the REAL send action at the bottom - what you see is what goes.
 * Own ViewModel (per-screen); the parent wires [onSent] back into the main
 * dialog's ViewModel, so this screen uses both.
 */
@Composable
fun EmailPreviewDialogRoot(
    request: EmailPreviewRequest,
    partyName: String,
    onClose: () -> Unit,
    onSent: (status: String) -> Unit,
    viewModel: EmailPreviewViewModel = koinViewModel(
        key = "email-preview-${request.clientCode}-${request.year}-${request.month}",
    ) { parametersOf(request) },
) {
    ObserveEffects(viewModel.events) { effect ->
        when (effect) {
            is EmailPreviewEffect.Sent -> onSent(effect.status)
        }
    }

    EmailPreviewDialog(
        state = viewModel.state,
        title = "Προεπισκόπιση — ${greekMonthName(request.month)} ${request.year} — $partyName",
        recipient = request.recipient,
        onIntent = viewModel::onAction,
        onClose = onClose,
    )
}

@Composable
private fun EmailPreviewDialog(
    state: EmailPreviewState,
    title: String,
    recipient: String,
    onIntent: (EmailPreviewIntent) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!state.sending) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.94f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    title,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                when {
                    state.loading -> Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                    }
                    state.html != null ->
                        EmailHtmlPreview(state.html, Modifier.weight(1f).fillMaxWidth())
                    else -> Text(
                        state.error ?: "Η προεπισκόπηση απέτυχε",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val err = state.error
                    if (err != null && state.html != null) {
                        Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    } else {
                        Text(
                            "Προς: $recipient",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    TextButton(enabled = !state.sending, onClick = onClose) { Text("Πίσω") }
                    TextButton(
                        enabled = !state.sending && state.html != null,
                        onClick = { onIntent(EmailPreviewIntent.Send) }
                    ) {
                        if (state.sending) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        } else {
                            Text("Αποστολή", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

internal fun greekMonthName(m: Int): String = listOf(
    "Ιανουάριος", "Φεβρουάριος", "Μάρτιος", "Απρίλιος", "Μάιος", "Ιούνιος",
    "Ιούλιος", "Αύγουστος", "Σεπτέμβριος", "Οκτώβριος", "Νοέμβριος", "Δεκέμβριος",
).getOrElse(m - 1) { m.toString() }
