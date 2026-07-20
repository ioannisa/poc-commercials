package eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable.components

import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.util.toStringKey
import androidx.compose.ui.layout.ContentScale
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.components.AppAsyncImage
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppGroupBox
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconSize
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadioColumn
import eu.anifantakis.commercials.core.presentation.design_system.components.AppSelectionOption
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.core.domain.auth.StationAccess
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FinderSelection
import eu.anifantakis.commercials.feature.timetable.presentation.screens.reportToolbarLabels
import eu.anifantakis.commercials.feature.timetable.presentation.screens.reportToolbarMetrics
import eu.anifantakis.commercials.reports.ui.ReportToolbar
import kotlinx.collections.immutable.ImmutableList
import eu.anifantakis.commercials.core.presentation.string_resources.LocalLanguage
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
import eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable.ShowBasedOn
import eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable.TimetableIntent
import eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable.TimetableScreenNavIntent
import eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable.TimetableState

/**
 * The grid screen's HEADER band - the legacy grouped-box toolbar as one
 * composite row, plus the pieces only it draws.
 *
 * Split out of TimetableScreen.kt purely for SIZE: this was ~494 lines of
 * layout (Μηνύματα over the report buttons, the break/programme boxes, the
 * two radio groups, the station picker, the selected-break readout, the
 * account cluster) sitting in the middle of the screen file. Nothing moved
 * but the text: it owns no state and no ViewModel, renders TimetableState
 * and forwards intents exactly as it did inline.
 */

/**
 * The three grid view modes as the "Προβολή κάθε" group box's options.
 * Remembered against the language: the header re-executes on every state tick,
 * and these labels only change on a language switch.
 */
@Composable
private fun viewModeOptions(): List<AppSelectionOption<GridViewMode>> {
    val language = LocalLanguage.current
    return remember(language) {
        listOf(
            AppSelectionOption(GridViewMode.HOURLY, StringKey.TIMETABLE_VIEW_HOURLY.localized()),
            AppSelectionOption(GridViewMode.HALF_HOURLY, StringKey.TIMETABLE_VIEW_HALF_HOURLY.localized()),
            AppSelectionOption(GridViewMode.CONDENSED, StringKey.TIMETABLE_VIEW_BREAK.localized()),
        )
    }
}

/**
 * The «Προβολή Βάσει…» options - whose airings the grid counts. Remembered
 * against the language, like [viewModeOptions] and for the same reason.
 */
@Composable
private fun showBasedOnOptions(): List<AppSelectionOption<ShowBasedOn>> {
    val language = LocalLanguage.current
    return remember(language) {
        listOf(
            AppSelectionOption(ShowBasedOn.ALL, StringKey.TIMETABLE_BASED_ON_ALL.localized()),
            AppSelectionOption(ShowBasedOn.PROGRAM, StringKey.TIMETABLE_BASED_ON_PROGRAM.localized()),
            AppSelectionOption(ShowBasedOn.CUSTOMER, StringKey.TIMETABLE_BASED_ON_CUSTOMER.localized()),
            AppSelectionOption(ShowBasedOn.CONTRACT, StringKey.TIMETABLE_BASED_ON_CONTRACT.localized()),
            AppSelectionOption(ShowBasedOn.MESSAGE, StringKey.TIMETABLE_BASED_ON_MESSAGE.localized()),
        )
    }
}

/**
 * Shows which station's data is on screen. With a single grant it's a plain
 * label; with several it becomes a dropdown - selecting one asks the ViewModel
 * to switch, and the session revision it raises refetches the data and
 * re-evaluates the role.
 */
@Composable
private fun StationSelector(
    stations: ImmutableList<StationAccess>,
    current: StationAccess?,
    onSelectStation: (String) -> Unit,
) {
    if (current == null) return
    var expanded by remember { mutableStateOf(false) }

    Box {
        if (stations.size > 1) {
            AppButton(onClick = { expanded = true }, variant = AppButtonVariant.TEXT) {
                AppText(
                    current.name,
                    AppTextStyle.SECTION_TITLE,
                    color = MaterialTheme.colorScheme.primary
                )
                AppIcon(
                    AppDrawableRepo.arrowDropDown,
                    contentDescription = Strings[StringKey.TIMETABLE_CD_SWITCH_STATION],
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                stations.forEach { station ->
                    DropdownMenuItem(
                        text = { AppText(station.name, AppTextStyle.BUTTON) },
                        leadingIcon = {
                            if (station.id == current.id) {
                                AppIcon(AppDrawableRepo.check, size = AppIconSize.SMALL)
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelectStation(station.id)
                        }
                    )
                }
            }
        } else {
            AppText(
                current.name,
                AppTextStyle.SECTION_TITLE,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = UIConst.paddingSmall)
            )
        }
    }
}

/**
 * The report toolbar (Προεπισκόπηση/Εκτύπωση/Εξαγωγή PDF) is hidden for now -
 * kept in code, gated here. Flip to true to bring it back; the desktop
 * menu-bar report commands stay wired regardless.
 */
private const val SHOW_REPORT_TOOLBAR = false

@Composable
internal fun KeyboardEnabledHeader(
    state: TimetableState,
    monthName: String,
    onIntent: (TimetableIntent) -> Unit,
    onNavIntent: (TimetableScreenNavIntent) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp
    ) {
        // The legacy grouped-box toolbar, as ONE composite row: a LEFT column
        // (the Μηνύματα box over the report buttons, equal widths) and
        // everything else to its RIGHT - the pending/real box band with the
        // logo/account cluster, and the month strip under them. Boxes for
        // features not migrated yet are grayed "pending" stubs. The Material
        // controls are compacted to the legacy's density: the radios otherwise
        // reserve a 48dp touch floor that balloons the whole band, and desktop
        // is mouse-first so a tight target is fine.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(horizontal = UIConst.paddingSmall, vertical = UIConst.paddingExtraSmall),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)
            ) {
                // ═══ LEFT column: Μηνύματα over the print section ═══════════
                // width(IntrinsicSize.Max) sizes the column to its widest
                // child (the three report buttons); the Μηνύματα box then
                // fills that same width, exactly as the legacy console pairs
                // them.
                Column(
                    modifier = Modifier.fillMaxHeight().width(IntrinsicSize.Max),
                    verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall),
                ) {
                    if (state.canEdit) {
                        // "Μηνύματα" - the legacy Messages box: the Εύρεση
                        // finder that arms the grid's 'a' key.
                        MessagesBox(
                            finder = state.finder,
                            onIntent = onIntent,
                            onOpenFinder = { onNavIntent(TimetableScreenNavIntent.OnOpenSpotFinder) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    // Report toolbar - Preview/Print/Export cover the entire
                    // month. Hidden for now (kept in code, gated).
                    if (SHOW_REPORT_TOOLBAR) {
                        ReportToolbar(
                            onPreview = { onIntent(TimetableIntent.PreviewMonth) },
                            onPrint = { onIntent(TimetableIntent.PrintMonth) },
                            onExportPdf = { onIntent(TimetableIntent.ExportMonthPdf) },
                            busy = state.reportBusy,
                            available = state.reportsAvailable,
                            labels = reportToolbarLabels(),
                            metrics = reportToolbarMetrics(),
                        )
                    }
                    // Month selector - stacked under Μηνύματα in the left
                    // column, like the legacy's ◀ Δεκέμβριος 2025 ▶ strip.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconButton(
                            label = Strings[StringKey.TIMETABLE_CD_PREV_MONTH],
                            icon = AppDrawableRepo.arrowBack,
                            onClick = { onIntent(TimetableIntent.PreviousMonth) },
                        )
                        AppText(
                            "$monthName ${state.year}",
                            AppTextStyle.SCREEN_TITLE,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            // weight, not a fixed box: the column's width is
                            // set by the print buttons; the title centers in it.
                            modifier = Modifier.weight(1f),
                        )
                        AppIconButton(
                            label = Strings[StringKey.TIMETABLE_CD_NEXT_MONTH],
                            icon = AppDrawableRepo.arrowForward,
                            onClick = { onIntent(TimetableIntent.NextMonth) },
                        )
                    }
                }

                // ═══ RIGHT side: the box band FILLS the full height (no gap),
                // logo/selector right after it, and the account cluster (top) +
                // keyboard hints (bottom) in a column at the far right.
                Row(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
                ) {
                    // The grouped boxes, each stretched to the FULL header height
                    // so none leaves a vertical gap. The first column stacks a
                    // pair, exactly like the legacy: [Πρόσθεση ⏐ Τύποι
                    // Προγράμματος] | Προβολή κάθε | Προβολή Βάσει….
                    Row(
                        modifier = Modifier.fillMaxHeight(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
                    ) {
                        if (state.canEdit) {
                            // "Πρόσθεση νέου διαλείματος" ⏐ "Τύποι Προγράμματος"
                            // - the programme console (ProgramConsole.kt).
                            // width(IntrinsicSize.Max): the column hugs its
                            // widest box; fillMaxWidth then EQUALIZES the pair
                            // without grabbing the whole row.
                            Column(
                                modifier = Modifier.fillMaxHeight().width(IntrinsicSize.Max),
                                verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall),
                            ) {
                                AddBreakBox(
                                    state = state,
                                    onIntent = onIntent,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                                ProgramBrushBox(
                                    state = state,
                                    onOpenConsole = {
                                        onNavIntent(TimetableScreenNavIntent.OnOpenProgramTypes)
                                    },
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                            }
                        }
                        // "Προβολή κάθε" (real): how much EMPTY scaffold is
                        // drawn - the real breaks are in every view, so no
                        // choice here hides one. Filled to the band height with
                        // its radios centered.
                        AppRadioColumn(
                            title = Strings[StringKey.TIMETABLE_VIEW_EVERY],
                            options = viewModeOptions(),
                            selected = state.viewMode,
                            onSelect = { onIntent(TimetableIntent.ViewModeChanged(it)) },
                            modifier = Modifier.fillMaxHeight(),
                            verticalArrangement = Arrangement.Center,
                        )
                        // "Προβολή Βάσει…" (real): WHOSE airings the cells
                        // count. The radios read their subject from the
                        // header's other selections - the programme dropdown,
                        // or the Εύρεση console's party/contract/spot - which
                        // only editors have, so viewer roles get no box.
                        if (state.canEdit) {
                            AppRadioColumn(
                                title = Strings[StringKey.TIMETABLE_VIEW_BASED_ON_TITLE],
                                options = showBasedOnOptions(),
                                selected = state.showBasedOn,
                                onSelect = { onIntent(TimetableIntent.ShowBasedOnChanged(it)) },
                                modifier = Modifier.fillMaxHeight(),
                                verticalArrangement = Arrangement.Center,
                            )
                        }

                        // Station selector ABOVE its logo, right after the boxes
                        // (where the legacy draws its brand mark): switching
                        // station swaps this very image, so the picker belongs
                        // over it. The repo entry resolves url + authenticated
                        // transport; Fit never crops a wordmark, no logo (404)
                        // leaves the slot empty.
                        Column(
                            modifier = Modifier.fillMaxHeight(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            StationSelector(
                                stations = state.stations,
                                current = state.selectedStation,
                                onSelectStation = { onIntent(TimetableIntent.SelectStation(it)) },
                            )
                            AppAsyncImage(
                                source = state.selectedStation?.let { AppDrawableRepo.stationLogo(it.id) },
                                contentDescription = state.selectedStation?.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(width = 132.dp, height = 34.dp),
                            )
                            Spacer(modifier = Modifier.height(UIConst.paddingSmall))
                            // The focused cell's break, painted with its
                            // programme's colour, day/date/time above it (legacy).
                            SelectedBreakReadout(
                                state = state,
                                onArmProgram = { onIntent(TimetableIntent.ArmCellProgram(it)) },
                                modifier = Modifier.padding(top = UIConst.paddingExtraSmall),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Far-right column: account/session controls at the top,
                    // keyboard hints at the bottom (the legacy's "?" help spot).
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)
                        ) {
                            // Email a party their schedule (staff action)
                            if (state.canEdit) {
                                AppIconButton(
                                    label = Strings[StringKey.TIMETABLE_CD_EMAIL_SCHEDULE],
                                    icon = AppDrawableRepo.email,
                                    onClick = { onNavIntent(TimetableScreenNavIntent.OnOpenEmailDialog) },
                                )
                            }
                            // Logged-in user: the badge opens the account menu
                            AccountBadge(
                                displayName = state.displayName,
                                isAdmin = state.isAdmin,
                                role = state.role,
                            )
                            // AI assistant launcher - only when the server holds
                            // at least one provider key (empty catalog = hidden).
                            if (state.aiChatEnabled) {
                                AppIconButton(
                                    label = Strings[StringKey.TIMETABLE_CD_AI_CHAT],
                                    icon = AppDrawableRepo.autoAwesome,
                                    onClick = { onNavIntent(TimetableScreenNavIntent.OnAiChat) },
                                )
                            }
                            AppIconButton(
                                label = Strings[StringKey.TIMETABLE_CD_PREFERENCES],
                                icon = AppDrawableRepo.settings,
                                onClick = { onNavIntent(TimetableScreenNavIntent.OnPreferences) },
                            )
                            AppIconButton(
                                label = Strings[StringKey.TIMETABLE_CD_LOGOUT],
                                icon = AppDrawableRepo.logout,
                                onClick = { onNavIntent(TimetableScreenNavIntent.OnLogout) },
                                tint = MaterialTheme.colorScheme.error,
                            )
                            // Cells: spot count <-> summed spot time (persisted;
                            // the icon shows the mode to switch TO)
                            AppIconButton(
                                label = Strings[if (state.showSpotTimes) StringKey.TIMETABLE_CD_SHOW_COUNTS else StringKey.TIMETABLE_CD_SHOW_TIMES],
                                icon = if (state.showSpotTimes) AppDrawableRepo.numbers else AppDrawableRepo.timer,
                                onClick = { onIntent(TimetableIntent.ToggleShowTimes) },
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Column(horizontalAlignment = Alignment.End) {
                            AppText(
                                Strings[if (state.canEdit) StringKey.TIMETABLE_HINT_KEYS_EDIT else StringKey.TIMETABLE_HINT_KEYS_VIEW],
                                AppTextStyle.TINY,
                            )
                            AppText(
                                Strings[StringKey.TIMETABLE_HINT_CLICK_FOCUS],
                                AppTextStyle.TINY,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The legacy "Μηνύματα" (Messages) box, now living in the header inside its
 * GroupBox: Εύρεση opens the Details Console; the dropdown switches among the
 * selected contract's spots (what the grid's 'a' key adds); the X clears the
 * finder (a fresh Εύρεση starts clean).
 */
@Composable
private fun MessagesBox(
    finder: FinderSelection,
    onIntent: (TimetableIntent) -> Unit,
    onOpenFinder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppGroupBox(
        title = Strings[StringKey.FINDER_SECTION_MESSAGES],
        // Centers the finder controls when the band stretches the box.
        verticalArrangement = Arrangement.Center,
        modifier = modifier,
    ) {
        // Whose contract the armed spot consumes - the legacy Messages box
        // shows the customer and contract above the finder controls, so the
        // operator always sees where the 'a'-key placements are billed.
        AppText(
            "${Strings[StringKey.FINDER_COL_NAME]}: ${finder.party?.name ?: "—"}",
            AppTextStyle.NOTE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AppText(
            "${Strings[StringKey.TIMETABLE_CONTRACT_LABEL]}: ${
                finder.line?.let { "${it.contractNumber} / ${it.lineNo}" } ?: "—"
            }",
            AppTextStyle.NOTE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Contract PRODUCT (the legacy's "Προϊόν ERP" line). The real ERP
        // product identity is pending the import, so a line shows gift / pending
        // - the same source the finder's contract table uses.
        AppText(
            "${Strings[StringKey.TIMETABLE_PRODUCT_LABEL]}: ${
                finder.line?.let {
                    if (it.isGift) Strings[StringKey.FINDER_GIFT_LINE] else Strings[StringKey.FINDER_ERP_PENDING]
                } ?: "—"
            }",
            AppTextStyle.NOTE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.heightIn(min = UIConst.paddingExtraSmall))
        // Εύρεση + the WIDE message/spot dropdown on ONE line (no extra height),
        // like the legacy "Μήνυμα" field. widthIn(min) on the row sets the box's
        // width so a large spot description has room; the X clears the finder.
        var spotMenu by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth().widthIn(min = 360.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppButton(
                text = Strings[StringKey.TIMETABLE_FINDER_BUTTON],
                onClick = onOpenFinder,
                variant = AppButtonVariant.SECONDARY,
            )
            Spacer(modifier = Modifier.width(UIConst.paddingSmall))
            Box(modifier = Modifier.weight(1f)) {
                AppButton(
                    onClick = { spotMenu = true },
                    enabled = finder.spots.isNotEmpty(),
                    fillMaxWidth = true,
                ) {
                    AppText(
                        finder.spot?.description ?: Strings[StringKey.TIMETABLE_NO_SPOT_SELECTED],
                        AppTextStyle.NOTE,
                        color = LocalContentColor.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    AppIcon(AppDrawableRepo.arrowDropDown)
                }
                DropdownMenu(expanded = spotMenu, onDismissRequest = { spotMenu = false }) {
                    finder.spots.forEach { spot ->
                        DropdownMenuItem(
                            text = { AppText("${spot.description} (${spot.durationSeconds}″)", AppTextStyle.NOTE, color = LocalContentColor.current) },
                            onClick = {
                                onIntent(TimetableIntent.FinderSpotSelected(spot))
                                spotMenu = false
                            }
                        )
                    }
                }
            }
            if (finder.party != null) {
                Spacer(modifier = Modifier.width(UIConst.paddingSmall))
                AppIconButton(
                    label = Strings[StringKey.TIMETABLE_CD_CLEAR_FINDER],
                    icon = AppDrawableRepo.clear,
                    onClick = { onIntent(TimetableIntent.ClearFinder) },
                )
            }
        }
    }
}

/**
 * The logged-in user badge. Regular users get self-service actions via the
 * Preferences screen (change password, recovery codes); the super admin's
 * credentials are managed in server.yaml, not through the API.
 */
@Composable
private fun AccountBadge(displayName: String, isAdmin: Boolean, role: AppRole) {
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.padding(horizontal = UIConst.paddingSmall, vertical = UIConst.paddingHairline)
    ) {
        // Single line + ellipsis: when the window (or the AI companion panel)
        // squeezes the toolbar, the name must truncate - never wrap into a
        // one-letter-per-line column.
        AppText(displayName, AppTextStyle.BODY_STRONG, maxLines = 1, overflow = TextOverflow.Ellipsis)
        AppText(
            if (isAdmin) Strings[StringKey.ROLE_SUPER_ADMIN] else Strings[role.toStringKey()],
            AppTextStyle.TINY,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
