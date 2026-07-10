package eu.anifantakis.commercials.feature.schedule_email.presentation.screens.send_schedule_email

import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.helper.ObserveEffects
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailPreviewRequest
import eu.anifantakis.commercials.feature.schedule_email.presentation.screens.email_preview.EmailPreviewDialogRoot
import eu.anifantakis.commercials.feature.schedule_email.presentation.screens.email_preview.monthName
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
        title = { AppText(Strings[StringKey.EMAIL_SEND_TITLE], AppTextStyle.DIALOG_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.done != null) {
                    AppText(state.done, AppTextStyle.BODY, color = MaterialTheme.colorScheme.primary)
                    return@Column
                }

                // party search: customers (spot owners) or traders (contract
                // payers - agencies in triangular deals)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = state.kind == PartyKind.CUSTOMER,
                        onClick = { onIntent(SendScheduleEmailIntent.KindChanged(PartyKind.CUSTOMER)) }
                    )
                    AppText(
                        Strings[StringKey.FINDER_TAB_CUSTOMERS], AppTextStyle.BODY,
                        modifier = Modifier.clickable {
                            onIntent(SendScheduleEmailIntent.KindChanged(PartyKind.CUSTOMER))
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    RadioButton(
                        selected = state.kind == PartyKind.TRADER,
                        onClick = { onIntent(SendScheduleEmailIntent.KindChanged(PartyKind.TRADER)) }
                    )
                    AppText(
                        Strings[StringKey.FINDER_TAB_ADVERTISERS], AppTextStyle.BODY,
                        modifier = Modifier.clickable {
                            onIntent(SendScheduleEmailIntent.KindChanged(PartyKind.TRADER))
                        }
                    )
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { onIntent(SendScheduleEmailIntent.QueryChanged(it)) },
                    label = {
                        AppText(
                            Strings[if (state.kind == PartyKind.CUSTOMER) StringKey.EMAIL_SEARCH_CUSTOMER
                            else StringKey.EMAIL_SEARCH_ADVERTISER],
                            AppTextStyle.FIELD_LABEL,
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
                            AppText(
                                Strings[StringKey.EMAIL_PARTY_SUMMARY].withArgs(listOf(c.name, c.spotCount, c.placementCount)),
                                AppTextStyle.BODY,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onIntent(SendScheduleEmailIntent.PartySelected(c)) }
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }
                } else if (state.query.trim().length >= 3 && !state.searching) {
                    AppText(Strings[StringKey.EMAIL_NO_RESULTS], AppTextStyle.NOTE)
                }

                state.selectedParty?.let { sel ->
                    AppText(
                        Strings[if (state.selectedKind == PartyKind.TRADER) StringKey.EMAIL_LABEL_ADVERTISER else StringKey.EMAIL_LABEL_CUSTOMER] + sel.name,
                        AppTextStyle.BODY_STRONG,
                    )

                    // year -> month drill-down over the party's airings
                    if (state.loadingActivity) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else if (state.activity.isEmpty()) {
                        AppText(Strings[StringKey.EMAIL_NO_ACTIVITY], AppTextStyle.NOTE)
                    } else {
                        Row(Modifier.fillMaxWidth().height(140.dp)) {
                            LazyColumn(Modifier.weight(0.35f).fillMaxHeight()) {
                                items(state.activity.map { it.year }.distinct(), key = { it }) { y ->
                                    val isSel = y == state.selectedYear
                                    AppText(
                                        "$y",
                                        if (isSel) AppTextStyle.BODY_STRONG else AppTextStyle.BODY,
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
                                        AppText(
                                            monthName(am.month),
                                            if (isSel) AppTextStyle.BODY_STRONG else AppTextStyle.BODY,
                                            color = if (isSel) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        AppText(
                                            "${am.placements}", AppTextStyle.BODY,
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
                        label = { AppText(Strings[StringKey.EMAIL_RECIPIENT_LABEL], AppTextStyle.FIELD_LABEL) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )

                    // spot checklist - each becomes a table in the one email
                    AppText(
                        Strings[StringKey.EMAIL_SPOTS_INCLUDED].withArgs(listOf(state.chosenSpotIds.size, state.spots.size)),
                        AppTextStyle.BODY_STRONG,
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
                                AppText(
                                    spot.description, AppTextStyle.NOTE,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                AppText("${spot.placements}", AppTextStyle.NOTE)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = state.note,
                        onValueChange = { onIntent(SendScheduleEmailIntent.NoteChanged(it)) },
                        label = { AppText(Strings[StringKey.EMAIL_PERSONAL_MESSAGE], AppTextStyle.FIELD_LABEL) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    AppText(
                        Strings[StringKey.EMAIL_ONE_EMAIL_NOTE].withArgs(listOf(state.chosenSpotIds.size)),
                        AppTextStyle.TINY,
                    )
                }

                // audit trail: prior sends to this party (anti double-send)
                if (state.selectedParty != null && state.history.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    AppText(Strings[StringKey.EMAIL_HISTORY], AppTextStyle.BODY_STRONG)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 96.dp)) {
                        items(state.history, key = { it.id }) { h ->
                            val failed = h.status != "SENT"
                            AppText(
                                "${h.sentAt.take(16)} · ${monthShort(h.month)} ${h.year} · ${h.recipient} · ${h.sentBy}" +
                                    if (failed) Strings[StringKey.EMAIL_FAILED_SUFFIX] else "",
                                AppTextStyle.TINY,
                                color = if (failed) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                state.error?.let { AppText(it.asString(), AppTextStyle.ERROR_NOTE) }
            }
        },
        confirmButton = {
            if (state.done != null) {
                TextButton(onClick = onDismiss) { AppText(Strings[StringKey.COMMON_CLOSE], AppTextStyle.BUTTON) }
            } else {
                TextButton(
                    enabled = state.canPreview,
                    onClick = { onIntent(SendScheduleEmailIntent.RequestPreview) }
                ) {
                    AppText(Strings[StringKey.EMAIL_PREVIEW_BUTTON], AppTextStyle.BUTTON)
                }
            }
        },
        dismissButton = {
            if (state.done == null) TextButton(onClick = onDismiss) { AppText(Strings[StringKey.COMMON_CANCEL], AppTextStyle.BUTTON) }
        }
    )
}

private fun monthShort(m: Int): String = listOf(
    StringKey.MONTH_SHORT_JANUARY, StringKey.MONTH_SHORT_FEBRUARY, StringKey.MONTH_SHORT_MARCH,
    StringKey.MONTH_SHORT_APRIL, StringKey.MONTH_SHORT_MAY, StringKey.MONTH_SHORT_JUNE,
    StringKey.MONTH_SHORT_JULY, StringKey.MONTH_SHORT_AUGUST, StringKey.MONTH_SHORT_SEPTEMBER,
    StringKey.MONTH_SHORT_OCTOBER, StringKey.MONTH_SHORT_NOVEMBER, StringKey.MONTH_SHORT_DECEMBER,
).getOrElse(m - 1) { null }?.localized() ?: m.toString()
