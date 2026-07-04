package eu.anifantakis.commercials.feature.schedule_email.presentation.screens.send_schedule_email

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.presentation.helper.ObserveEffects
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailPreviewRequest
import eu.anifantakis.commercials.feature.schedule_email.presentation.screens.email_preview.EmailPreviewDialogRoot
import eu.anifantakis.commercials.feature.schedule_email.presentation.screens.email_preview.greekMonthName
import org.koin.compose.viewmodel.koinViewModel

/**
 * Staff action: email a party their month's schedule. ONE email is sent,
 * with one grid per spot (creative) so it's clear which spots aired when.
 *
 * Flow: debounced party search (core PartySearchRepository) -> the party's
 * YEARS with airings -> the chosen year's MONTHS with counts -> the month's
 * spots + recipient/note -> "Προεπισκόπιση" opens the preview screen, which
 * has its OWN ViewModel and reports the send back through [onSent] wiring -
 * this Root uses both.
 */
@Composable
fun SendScheduleEmailDialogRoot(
    onDismiss: () -> Unit,
    viewModel: SendScheduleEmailViewModel = koinViewModel(),
) {
    var previewRequest by remember { mutableStateOf<EmailPreviewRequest?>(null) }

    ObserveEffects(viewModel.events) { effect ->
        when (effect) {
            is SendScheduleEmailEffect.OpenPreview -> previewRequest = effect.request
        }
    }

    SendScheduleEmailDialog(
        state = viewModel.state,
        onIntent = viewModel::onAction,
        onDismiss = onDismiss,
    )

    previewRequest?.let { request ->
        EmailPreviewDialogRoot(
            request = request,
            partyName = viewModel.state.selectedParty?.name ?: request.clientCode,
            onClose = { previewRequest = null },
            onSent = { status ->
                viewModel.onAction(SendScheduleEmailIntent.MarkSent(status))
                previewRequest = null
            },
        )
    }
}

@Composable
private fun SendScheduleEmailDialog(
    state: SendScheduleEmailState,
    onIntent: (SendScheduleEmailIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Αποστολή προγραμματισμού") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.done != null) {
                    Text(state.done, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                    return@Column
                }

                // party search: customers (spot owners) or traders (contract
                // payers - agencies in triangular deals)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = state.kind == PartyKind.CUSTOMER,
                        onClick = { onIntent(SendScheduleEmailIntent.KindChanged(PartyKind.CUSTOMER)) }
                    )
                    Text(
                        "Πελάτες", fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            onIntent(SendScheduleEmailIntent.KindChanged(PartyKind.CUSTOMER))
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    RadioButton(
                        selected = state.kind == PartyKind.TRADER,
                        onClick = { onIntent(SendScheduleEmailIntent.KindChanged(PartyKind.TRADER)) }
                    )
                    Text(
                        "Διαφημιστές", fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            onIntent(SendScheduleEmailIntent.KindChanged(PartyKind.TRADER))
                        }
                    )
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { onIntent(SendScheduleEmailIntent.QueryChanged(it)) },
                    label = {
                        Text(
                            if (state.kind == PartyKind.CUSTOMER) "Αναζήτηση πελάτη (3+ χαρακτήρες)"
                            else "Αναζήτηση διαφημιστή (3+ χαρακτήρες)"
                        )
                    },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (state.searching) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        }
                    }
                )
                if (state.results.isNotEmpty()) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                        items(state.results, key = { it.code }) { c ->
                            Text(
                                "${c.name} — ${c.spotCount} σποτ, ${c.placementCount} μεταδόσεις",
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onIntent(SendScheduleEmailIntent.PartySelected(c)) }
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }
                } else if (state.query.trim().length >= 3 && !state.searching) {
                    Text(
                        "Κανένα αποτέλεσμα",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.selectedParty?.let { sel ->
                    Text(
                        (if (state.selectedKind == PartyKind.TRADER) "Διαφημιστής: " else "Πελάτης: ") + sel.name,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )

                    // year -> month drill-down over the party's airings
                    if (state.loadingActivity) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else if (state.activity.isEmpty()) {
                        Text("Καμία κίνηση", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(Modifier.fillMaxWidth().height(140.dp)) {
                            LazyColumn(Modifier.weight(0.35f).fillMaxHeight()) {
                                items(state.activity.map { it.year }.distinct(), key = { it }) { y ->
                                    val isSel = y == state.selectedYear
                                    Text(
                                        "$y",
                                        fontSize = 13.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onIntent(SendScheduleEmailIntent.YearSelected(y)) }
                                            .padding(vertical = 6.dp)
                                    )
                                }
                            }
                            LazyColumn(Modifier.weight(0.65f).fillMaxHeight()) {
                                items(
                                    state.activity.filter { it.year == state.selectedYear },
                                    key = { it.year * 100 + it.month }
                                ) { am ->
                                    val isSel = am.month == state.selectedMonth
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onIntent(SendScheduleEmailIntent.MonthSelected(am.year, am.month))
                                            }
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            greekMonthName(am.month),
                                            fontSize = 13.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSel) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            "${am.placements}", fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.selectedParty != null && state.selectedMonth != null) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = state.recipient,
                        onValueChange = { onIntent(SendScheduleEmailIntent.RecipientChanged(it)) },
                        label = { Text("Παραλήπτης (email)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )

                    // spot checklist - each becomes a table in the one email
                    Text(
                        "Σποτ που θα περιληφθούν (${state.chosenSpotIds.size}/${state.spots.size})",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                        items(state.spots, key = { it.spotId }) { spot ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onIntent(SendScheduleEmailIntent.SpotToggled(spot.spotId))
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = spot.spotId in state.includedSpotIds,
                                    onCheckedChange = { onIntent(SendScheduleEmailIntent.SpotToggled(spot.spotId)) }
                                )
                                Text(spot.description, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(
                                    "${spot.placements}", fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = state.note,
                        onValueChange = { onIntent(SendScheduleEmailIntent.NoteChanged(it)) },
                        label = { Text("Προσωπικό μήνυμα (προαιρετικό)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Θα σταλεί ΕΝΑ email με ${state.chosenSpotIds.size} πίνακες (ένας ανά σποτ).",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // audit trail: prior sends to this party (anti double-send)
                if (state.selectedParty != null && state.history.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Ιστορικό αποστολών", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 96.dp)) {
                        items(state.history, key = { it.id }) { h ->
                            val failed = h.status != "SENT"
                            Text(
                                "${h.sentAt.take(16)} · ${monthShort(h.month)} ${h.year} · ${h.recipient} · ${h.sentBy}" +
                                    if (failed) " · ΑΠΕΤΥΧΕ" else "",
                                fontSize = 11.sp,
                                color = if (failed) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            if (state.done != null) {
                TextButton(onClick = onDismiss) { Text("Κλείσιμο") }
            } else {
                TextButton(
                    enabled = state.canPreview,
                    onClick = { onIntent(SendScheduleEmailIntent.RequestPreview) }
                ) {
                    Text("Προεπισκόπιση")
                }
            }
        },
        dismissButton = {
            if (state.done == null) TextButton(onClick = onDismiss) { Text("Άκυρο") }
        }
    )
}

private fun monthShort(m: Int): String = listOf(
    "Ιαν", "Φεβ", "Μαρ", "Απρ", "Μαϊ", "Ιουν", "Ιουλ", "Αυγ", "Σεπ", "Οκτ", "Νοε", "Δεκ"
).getOrElse(m - 1) { m.toString() }
