package eu.anifantakis.commercials.feature.schedule_email.presentation.screens.send_schedule_email

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCheckbox
import eu.anifantakis.commercials.core.presentation.design_system.components.AppDialog
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadioRow
import eu.anifantakis.commercials.core.presentation.design_system.components.AppSpinner
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
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

    // The ViewModel outlives this dialog (it is store-scoped, the dialog is a
    // show/hide boolean), so a fresh open must clear the previous run - otherwise
    // reopening after a send shows the old "sent" confirmation, not an empty form.
    LaunchedEffect(Unit) { viewModel.onAction(SendScheduleEmailIntent.Reset) }

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
    AppDialog(
        title = Strings[StringKey.EMAIL_SEND_TITLE],
        onDismiss = onDismiss,
        confirmText = Strings[if (state.done != null) StringKey.COMMON_CLOSE else StringKey.EMAIL_PREVIEW_BUTTON],
        onConfirm = {
            if (state.done != null) onDismiss()
            else onIntent(SendScheduleEmailIntent.RequestPreview)
        },
        dismissText = if (state.done == null) Strings[StringKey.COMMON_CANCEL] else null,
        confirmEnabled = state.done != null || state.canPreview,
    ) {
        if (state.done != null) {
            AppText(state.done, AppTextStyle.BODY, color = MaterialTheme.colorScheme.primary)
            return@AppDialog
        }

        // party search: customers (spot owners) or traders (contract
        // payers - agencies in triangular deals)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppRadioRow(
                selected = state.kind == PartyKind.CUSTOMER,
                onClick = { onIntent(SendScheduleEmailIntent.KindChanged(PartyKind.CUSTOMER)) },
                label = Strings[StringKey.FINDER_TAB_CUSTOMERS],
            )
            Spacer(Modifier.width(UIConst.paddingCompact))
            AppRadioRow(
                selected = state.kind == PartyKind.TRADER,
                onClick = { onIntent(SendScheduleEmailIntent.KindChanged(PartyKind.TRADER)) },
                label = Strings[StringKey.FINDER_TAB_ADVERTISERS],
            )
        }
        AppTextField(
            value = state.query,
            onValueChange = { onIntent(SendScheduleEmailIntent.QueryChanged(it)) },
            label = Strings[if (state.kind == PartyKind.CUSTOMER) StringKey.EMAIL_SEARCH_CUSTOMER
            else StringKey.EMAIL_SEARCH_ADVERTISER],
            trailingIcon = { if (state.searching) AppSpinner() },
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
                            .padding(vertical = UIConst.paddingSmall)
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
                AppSpinner()
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
                                    .padding(vertical = UIConst.paddingSmall)
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
                                    .padding(vertical = UIConst.paddingSmall),
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
            Spacer(Modifier.height(UIConst.paddingExtraSmall))

            val recipientHint = state.customerEmail.takeIf { it.isNotBlank() } ?: Strings[StringKey.EMAIL_RECIPIENT_LABEL]
            AppTextField(
                value = state.recipient,
                onValueChange = { onIntent(SendScheduleEmailIntent.RecipientChanged(it)) },
                label = recipientHint,
                labelFocused = Strings[StringKey.EMAIL_RECIPIENT_LABEL],
                // The customer's stored email shows FAINT as the placeholder: leave
                // the field blank to send there, or type to override. Empty (no stored
                // email) means a recipient is mandatory - the Preview button stays
                // disabled until one is entered (canPreview).
                placeholder = recipientHint,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
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
                        AppCheckbox(
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

            AppTextField(
                value = state.note,
                onValueChange = { onIntent(SendScheduleEmailIntent.NoteChanged(it)) },
                label = Strings[StringKey.EMAIL_PERSONAL_MESSAGE],
                singleLine = false,
            )
            AppText(
                Strings[StringKey.EMAIL_ONE_EMAIL_NOTE].withArgs(listOf(state.chosenSpotIds.size)),
                AppTextStyle.TINY,
            )
        }

        // audit trail: prior sends to this party (anti double-send)
        if (state.selectedParty != null && state.history.isNotEmpty()) {
            Spacer(Modifier.height(UIConst.paddingExtraSmall))
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
}

private fun monthShort(m: Int): String = listOf(
    StringKey.MONTH_SHORT_JANUARY, StringKey.MONTH_SHORT_FEBRUARY, StringKey.MONTH_SHORT_MARCH,
    StringKey.MONTH_SHORT_APRIL, StringKey.MONTH_SHORT_MAY, StringKey.MONTH_SHORT_JUNE,
    StringKey.MONTH_SHORT_JULY, StringKey.MONTH_SHORT_AUGUST, StringKey.MONTH_SHORT_SEPTEMBER,
    StringKey.MONTH_SHORT_OCTOBER, StringKey.MONTH_SHORT_NOVEMBER, StringKey.MONTH_SHORT_DECEMBER,
).getOrElse(m - 1) { null }?.localized() ?: m.toString()
