package eu.anifantakis.commercials.feature.schedule_email.presentation.screens.send_schedule_email

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconSize
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadioRow
import eu.anifantakis.commercials.core.presentation.design_system.components.AppSpinner
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.helper.ObserveEffects
import eu.anifantakis.commercials.core.presentation.string_resources.LocalLanguage
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailActivityMonth
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailLogEntry
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailPreviewRequest
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailSpot
import eu.anifantakis.commercials.grids.ColumnDef
import eu.anifantakis.commercials.grids.EnhancedDataGrid
import eu.anifantakis.commercials.grids.SelectionMode
import eu.anifantakis.commercials.grids.StickyRowsConfig
import eu.anifantakis.commercials.grids.rememberEnhancedDataGridState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import eu.anifantakis.commercials.feature.schedule_email.presentation.screens.email_preview.EmailPreviewDialogRoot
import eu.anifantakis.commercials.feature.schedule_email.presentation.screens.email_preview.monthName
import org.koin.compose.viewmodel.koinViewModel

/**
 * Staff action: email a party their month's schedule. ONE email is sent,
 * with one grid per spot (creative) so it's clear which spots aired when.
 *
 * Flow: debounced party search (core PartySearchRepository) -> the party's
 * YEARS with airings -> the chosen year's MONTHS with counts -> the month's
 * spots + recipient/note -> "Προεπισκόπιση" opens the preview, which has its
 * OWN ViewModel and reports the send back through [onSent] wiring - this Root
 * uses both.
 *
 * Hosted in a floating WINDOW (AppWindowHost) with its own keyed ViewModel
 * scope. That scoping is load-bearing here: this used to be a show/hide
 * boolean over a store-scoped ViewModel that outlived it, so the Root had to
 * fire a `Reset` intent on every open or a reopen showed the PREVIOUS run's
 * "sent" confirmation instead of an empty form. Closing the window now
 * destroys the ViewModel, so a fresh open is empty by construction - and
 * minimizing keeps a half-composed email intact.
 */
@Composable
fun SendScheduleEmailScreenRoot(
    onClose: () -> Unit,
    viewModel: SendScheduleEmailViewModel = koinViewModel(),
) {
    var previewRequest by remember { mutableStateOf<EmailPreviewRequest?>(null) }

    ObserveEffects(viewModel.events) { effect ->
        when (effect) {
            is SendScheduleEmailEffect.OpenPreview -> previewRequest = effect.request
        }
    }

    SendScheduleEmailScreen(
        state = viewModel.state,
        onIntent = viewModel::onAction,
        onClose = onClose,
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
private fun SendScheduleEmailScreen(
    state: SendScheduleEmailState,
    onIntent: (SendScheduleEmailIntent) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(UIConst.paddingRegular)) {
        // The form scrolls INSIDE the window: the window's height is the
        // operator's choice, and a long party history must not push the
        // action row off the bottom.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
            SendScheduleEmailForm(state = state, onIntent = onIntent)
        }
        Spacer(Modifier.height(UIConst.paddingSmall))
        // The window chrome owns the title and the ✕; this row owns the
        // workflow's own actions, which AppDialog used to supply.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
            Spacer(Modifier.weight(1f))
            if (state.done == null) {
                AppButton(
                    text = Strings[StringKey.COMMON_CANCEL],
                    onClick = onClose,
                    variant = AppButtonVariant.TEXT,
                )
            }
            AppButton(
                text = Strings[if (state.done != null) StringKey.COMMON_CLOSE else StringKey.EMAIL_PREVIEW_BUTTON],
                onClick = {
                    if (state.done != null) onClose()
                    else onIntent(SendScheduleEmailIntent.RequestPreview)
                },
                enabled = state.done != null || state.canPreview,
            )
        }
    }
}

@Composable
private fun ColumnScope.SendScheduleEmailForm(
    state: SendScheduleEmailState,
    onIntent: (SendScheduleEmailIntent) -> Unit,
) {
        if (state.done != null) {
            AppText(state.done, AppTextStyle.BODY, color = MaterialTheme.colorScheme.primary)
            return
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
            // Was one glued sentence per row ("name — N spots, M placements").
            // As a grid the columns line up and the party's stored EMAIL is
            // visible while choosing - which is the whole point here: it is
            // the address the schedule will go to unless overridden below.
            EmailGrid(
                items = state.results,
                columns = partyColumns(),
                selectedKey = state.selectedParty?.code,
                rowKey = { it.code },
                onRowClick = { onIntent(SendScheduleEmailIntent.PartySelected(it)) },
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
        } else if (state.query.trim().length >= 3 && !state.searching) {
            AppText(Strings[StringKey.EMAIL_NO_RESULTS], AppTextStyle.NOTE)
        }

        state.selectedParty?.let { sel ->
            AppText(
                Strings[if (state.selectedKind == PartyKind.TRADER) StringKey.EMAIL_LABEL_ADVERTISER else StringKey.EMAIL_LABEL_CUSTOMER] + sel.name,
                AppTextStyle.BODY_STRONG,
            )

            // The month picker and the month's spots, SIDE BY SIDE: picking a
            // month on the left fills the right, and the operator sees the
            // consequence of the choice without scrolling. (The picker itself
            // used to be two unrelated LazyColumns whose rows lined up by
            // accident - "2026 | December | 174" was row 1 of one list beside
            // row 1 of another. One grid, one row per month.)
            Row(
                modifier = Modifier.fillMaxWidth().height(GRID_HEIGHT),
                horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
            ) {
                Column(Modifier.weight(0.45f).fillMaxHeight()) {
                    if (state.loadingActivity) {
                        AppSpinner()
                    } else if (state.activity.isEmpty()) {
                        AppText(Strings[StringKey.EMAIL_NO_ACTIVITY], AppTextStyle.NOTE)
                    } else {
                        EmailGrid(
                            items = state.activity,
                            columns = activityColumns(),
                            selectedKey = state.selectedMonth?.let { m ->
                                state.selectedYear?.let { y -> y * 100 + m }
                            },
                            rowKey = { it.year * 100 + it.month },
                            onRowClick = { onIntent(SendScheduleEmailIntent.MonthSelected(it.year, it.month)) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Column(Modifier.weight(0.55f).fillMaxHeight()) {
                    if (state.selectedMonth == null) {
                        AppText(Strings[StringKey.EMAIL_PICK_MONTH_FIRST], AppTextStyle.NOTE)
                    } else {
                        // Each ticked spot becomes one table in the single email.
                        AppText(
                            Strings[StringKey.EMAIL_SPOTS_INCLUDED]
                                .withArgs(listOf(state.chosenSpotIds.size, state.spots.size)),
                            AppTextStyle.BODY_STRONG,
                        )
                        EmailGrid(
                            items = state.spots,
                            columns = spotColumns(state.includedSpotIds),
                            selectedKey = null,   // inclusion is the tick, NOT row selection
                            rowKey = { it.spotId },
                            // The ONLY toggle path. The tick is drawn as a plain
                            // icon, not a Checkbox: an interactive checkbox fired
                            // its own onCheckedChange AND this row click, so every
                            // press toggled twice and nothing ever changed.
                            onRowClick = { onIntent(SendScheduleEmailIntent.SpotToggled(it.spotId)) },
                            modifier = Modifier.fillMaxSize(),
                        )
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

        // audit trail: prior sends to this party (anti double-send). Was one
        // dot-joined string per row, which hid WHICH field was which; as a
        // grid the recipient and the period line up and can be sorted.
        if (state.selectedParty != null && state.history.isNotEmpty()) {
            Spacer(Modifier.height(UIConst.paddingExtraSmall))
            AppText(Strings[StringKey.EMAIL_HISTORY], AppTextStyle.BODY_STRONG)
            EmailGrid(
                items = state.history,
                columns = historyColumns(),
                selectedKey = null,      // an audit trail is read-only
                rowKey = { it.id },
                onRowClick = null,
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
        }

        state.error?.let { AppText(it.asString(), AppTextStyle.ERROR_NOTE) }
}

/**
 * Height of the month/spots pair. They sit in one Row, so they must agree -
 * a shared constant rather than two numbers that drift apart.
 */
private val GRID_HEIGHT = 190.dp

/**
 * One table of the composer, on the SAME [EnhancedDataGrid] the timetable
 * consoles use - so all four tables in the app share resizable/reorderable
 * columns, a sticky header, sortable headers and scrollbars.
 *
 * Selection is the CALLER's ([selectedKey]), not the grid's: a click
 * dispatches an intent and the highlight is re-derived when the list
 * changes. Matched by KEY, never by index, so a sorted header cannot put the
 * highlight on the wrong row. Pass a null [selectedKey] for a table whose
 * rows are not "chosen" (the spot checklist, the audit trail).
 */
@Composable
private fun <T> EmailGrid(
    items: ImmutableList<T>,
    columns: ImmutableList<ColumnDef<T>>,
    selectedKey: Any?,
    rowKey: (T) -> Any,
    onRowClick: ((T) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberEnhancedDataGridState(columns)
    LaunchedEffect(items, selectedKey == null) {
        val index = selectedKey?.let { key -> items.indexOfFirst { rowKey(it) == key } } ?: -1
        if (index >= 0) {
            gridState.selectedRows = setOf(index)
            gridState.focusedRow = index
        } else {
            gridState.clearSelection()
        }
    }
    val empty = Strings[StringKey.EMAIL_NO_RESULTS]
    EnhancedDataGrid(
        items = items,
        columns = columns,
        modifier = modifier,
        state = gridState,
        selectionMode = if (selectedKey == null) SelectionMode.NONE else SelectionMode.SINGLE,
        // The composer stacks several tables in one window - denser than a
        // full-screen grid, same scale rule as the Εύρεση console.
        scale = AppTheme.fontSizeStep.factor,
        rowHeight = 26.dp,
        headerHeight = 30.dp,
        showRowNumbers = false,
        stickyRows = StickyRowsConfig(stickyHeader = true, stickyFooter = false),
        onRowClick = onRowClick?.let { click -> { item, _ -> click(item) } },
        rowKey = rowKey,
        emptyContent = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppText(empty, AppTextStyle.NOTE, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/*
 * Column definitions. Remembered against the language (headers resolve
 * through the non-composable .localized()) and the font-size step - the grid
 * scales its own type, but column WIDTHS live here, so a bigger step must
 * rebuild them wider or the text merely ellipsizes.
 */

@Composable
private fun partyColumns(): ImmutableList<ColumnDef<Party>> {
    val language = LocalLanguage.current
    val scale = AppTheme.fontSizeStep.factor
    return remember(language, scale) {
        persistentListOf(
            ColumnDef<Party>(
                id = "code",
                header = StringKey.FINDER_COL_CODE.localized(),
                width = 110.dp * scale,
                extractor = { it.code },
            ),
            ColumnDef(
                id = "name",
                header = StringKey.FINDER_COL_NAME.localized(),
                width = 300.dp * scale,
                extractor = { it.name },
            ),
            ColumnDef(
                id = "email",
                header = StringKey.EMAIL_COL_RECIPIENT.localized(),
                width = 230.dp * scale,
                extractor = { it.email ?: "" },
            ),
            ColumnDef(
                id = "spots",
                header = StringKey.EMAIL_COL_SPOTS.localized(),
                width = 100.dp * scale,
                alignment = TextAlign.End,
                headerAlignment = TextAlign.End,
                extractor = { it.spotCount.toString() },
                comparator = compareBy { it.spotCount },
            ),
        )
    }
}

@Composable
private fun activityColumns(): ImmutableList<ColumnDef<EmailActivityMonth>> {
    val language = LocalLanguage.current
    val scale = AppTheme.fontSizeStep.factor
    return remember(language, scale) {
        persistentListOf(
            ColumnDef<EmailActivityMonth>(
                id = "year",
                header = StringKey.EMAIL_COL_YEAR.localized(),
                width = 90.dp * scale,
                extractor = { it.year.toString() },
                comparator = compareBy({ it.year }, { it.month }),
            ),
            ColumnDef(
                id = "month",
                header = StringKey.EMAIL_COL_MONTH.localized(),
                width = 160.dp * scale,
                extractor = { monthName(it.month) },
                // Sort by the NUMBER - alphabetical month names are nonsense.
                comparator = compareBy({ it.year }, { it.month }),
            ),
            ColumnDef(
                id = "placements",
                header = StringKey.EMAIL_COL_SPOTS.localized(),
                width = 110.dp * scale,
                alignment = TextAlign.End,
                headerAlignment = TextAlign.End,
                extractor = { it.placements.toString() },
                comparator = compareBy { it.placements },
            ),
        )
    }
}

@Composable
private fun spotColumns(included: ImmutableSet<Long>): ImmutableList<ColumnDef<EmailSpot>> {
    val language = LocalLanguage.current
    val scale = AppTheme.fontSizeStep.factor
    val tickColor = MaterialTheme.colorScheme.primary
    return remember(language, scale, included, tickColor) {
        persistentListOf(
            ColumnDef<EmailSpot>(
                id = "included",
                header = "",
                width = 44.dp * scale,
                resizable = false,
                sortable = true,
                comparator = compareBy { it.spotId in included },
                extractor = { "" },
                // A DISPLAY tick, deliberately not a Checkbox. A real
                // Checkbox handles its own click and the grid also fires
                // onRowClick for the same press: the two toggles cancelled
                // out and the box looked permanently ticked. An icon draws
                // the state and leaves the single row-click path alone.
                cellContent = { spot, _, _ ->
                    if (spot.spotId in included) {
                        AppIcon(
                            AppDrawableRepo.check,
                            contentDescription = null,
                            size = AppIconSize.SMALL,
                            tint = tickColor,
                        )
                    }
                },
            ),
            ColumnDef(
                id = "description",
                header = StringKey.EMAIL_COL_SPOT.localized(),
                width = 420.dp * scale,
                extractor = { it.description },
            ),
            ColumnDef(
                id = "placements",
                header = StringKey.EMAIL_COL_SPOTS.localized(),
                width = 110.dp * scale,
                alignment = TextAlign.End,
                headerAlignment = TextAlign.End,
                extractor = { it.placements.toString() },
                comparator = compareBy { it.placements },
            ),
        )
    }
}

@Composable
private fun historyColumns(): ImmutableList<ColumnDef<EmailLogEntry>> {
    val language = LocalLanguage.current
    val scale = AppTheme.fontSizeStep.factor
    val errorColor = MaterialTheme.colorScheme.error
    val normalColor = MaterialTheme.colorScheme.onSurface
    return remember(language, scale, errorColor, normalColor) {
        // A failed send is the whole point of the audit trail - colour the
        // ROW, not a "· FAILED" suffix glued onto the end of a sentence.
        val failedTint: (EmailLogEntry) -> Color = { if (it.status != "SENT") errorColor else normalColor }
        persistentListOf(
            ColumnDef<EmailLogEntry>(
                id = "sentAt",
                header = StringKey.EMAIL_COL_SENT_AT.localized(),
                width = 150.dp * scale,
                extractor = { it.sentAt.take(16) },
                cellTextColor = failedTint,
                comparator = compareBy { it.sentAt },
            ),
            ColumnDef(
                id = "period",
                header = StringKey.EMAIL_COL_PERIOD.localized(),
                width = 120.dp * scale,
                extractor = { "${monthShort(it.month)} ${it.year}" },
                cellTextColor = failedTint,
                comparator = compareBy({ it.year }, { it.month }),
            ),
            ColumnDef(
                id = "recipient",
                header = StringKey.EMAIL_COL_RECIPIENT.localized(),
                width = 260.dp * scale,
                extractor = { it.recipient },
                cellTextColor = failedTint,
            ),
            ColumnDef(
                id = "sentBy",
                header = StringKey.EMAIL_COL_SENT_BY.localized(),
                width = 150.dp * scale,
                extractor = { it.sentBy },
                cellTextColor = failedTint,
            ),
        )
    }
}

private fun monthShort(m: Int): String = listOf(
    StringKey.MONTH_SHORT_JANUARY, StringKey.MONTH_SHORT_FEBRUARY, StringKey.MONTH_SHORT_MARCH,
    StringKey.MONTH_SHORT_APRIL, StringKey.MONTH_SHORT_MAY, StringKey.MONTH_SHORT_JUNE,
    StringKey.MONTH_SHORT_JULY, StringKey.MONTH_SHORT_AUGUST, StringKey.MONTH_SHORT_SEPTEMBER,
    StringKey.MONTH_SHORT_OCTOBER, StringKey.MONTH_SHORT_NOVEMBER, StringKey.MONTH_SHORT_DECEMBER,
).getOrElse(m - 1) { null }?.localized() ?: m.toString()
