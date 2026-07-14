package eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable

import kotlinx.datetime.DayOfWeek
import eu.anifantakis.commercials.grids.SchedulerLabels
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.core.presentation.util.toStringKey
import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconSize
import eu.anifantakis.commercials.core.presentation.design_system.components.AppPopup
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadioRow
import eu.anifantakis.commercials.core.presentation.design_system.components.AppSpinner
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.core.domain.auth.StationAccess
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.presentation.commands.AppCommand
import eu.anifantakis.commercials.core.presentation.commands.CommandRegistry
import eu.anifantakis.commercials.core.presentation.commands.RegisterAppCommand
import eu.anifantakis.commercials.core.presentation.helper.ObserveEffects
import org.koin.compose.koinInject
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import eu.anifantakis.commercials.grids.BreakSlot
import eu.anifantakis.commercials.grids.BreakZone
import eu.anifantakis.commercials.grids.ContextMenuEntry
import eu.anifantakis.commercials.grids.LazySchedulerGrid
import eu.anifantakis.commercials.grids.SchedulerCellData
import eu.anifantakis.commercials.grids.SchedulerKey
import eu.anifantakis.commercials.grids.formatTime
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.calculateDailyTotals
import eu.anifantakis.commercials.feature.timetable.presentation.screens.reportToolbarLabels
import eu.anifantakis.commercials.feature.timetable.presentation.screens.reportToolbarMetrics
import eu.anifantakis.commercials.reports.ui.ReportToolbar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import eu.anifantakis.commercials.core.presentation.string_resources.LocalLanguage
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode

/**
 * The scheduler grid screen (grid + its Εύρεση finder dialog). Per-screen
 * ViewModel; the cells themselves live in ScheduleCellsStore, the narrow
 * shared truth the detail screen's own ViewModel also observes and edits.
 *
 * Keyboard: arrows navigate, Enter opens, A/Α adds the finder-armed spot,
 * R/Ρ removes the session's last add in the cell.
 */
@Composable
fun TimetableScreenRoot(
    viewModel: TimetableViewModel,
    onOpenDetail: (time: LocalTime, date: LocalDate) -> Unit,
    onOpenEmailDialog: () -> Unit,
    onLogout: () -> Unit,
    onPreferences: () -> Unit,
) {
    ObserveEffects(viewModel.events) { effect ->
        when (effect) {
            is TimetableEffect.OpenDetail -> onOpenDetail(effect.time, effect.date)
        }
    }

    // App-chrome commands (desktop MenuBar / shortcuts): this screen owns the
    // report + email + account actions while it is composed; the registry
    // greys the menu items out everywhere else. Handlers route onto EXISTING
    // intents/callbacks only.
    val commandRegistry = koinInject<CommandRegistry>()
    val owner = "TimetableScreen"
    RegisterAppCommand(commandRegistry, owner, AppCommand.EXPORT_PDF, enabled = viewModel.state.reportsAvailable) {
        viewModel.onAction(TimetableIntent.ExportMonthPdf)
    }
    RegisterAppCommand(commandRegistry, owner, AppCommand.PRINT_REPORT, enabled = viewModel.state.reportsAvailable) {
        viewModel.onAction(TimetableIntent.PrintMonth)
    }
    RegisterAppCommand(commandRegistry, owner, AppCommand.PREVIEW_REPORT, enabled = viewModel.state.reportsAvailable) {
        viewModel.onAction(TimetableIntent.PreviewMonth)
    }
    RegisterAppCommand(commandRegistry, owner, AppCommand.SEND_SCHEDULE_EMAIL) { onOpenEmailDialog() }
    RegisterAppCommand(commandRegistry, owner, AppCommand.PREFERENCES) { onPreferences() }
    RegisterAppCommand(commandRegistry, owner, AppCommand.LOGOUT) { onLogout() }

    TimetableScreen(
        state = viewModel.state,
        onIntent = viewModel::onAction,
        onNavIntent = { navIntent ->
            when (navIntent) {
                TimetableScreenNavIntent.OnOpenEmailDialog -> onOpenEmailDialog()
                TimetableScreenNavIntent.OnLogout -> onLogout()
                TimetableScreenNavIntent.OnPreferences -> onPreferences()
            }
        },
    )
}

/**
 * Navigation-only actions of the grid screen. These are NOT ViewModel
 * [TimetableIntent]s (they touch no state and the ViewModel never sees them),
 * but collapsing them into ONE parameter keeps them from bloating the
 * signature. The Root maps each to the nav callback it received.
 */
private sealed interface TimetableScreenNavIntent {
    data object OnOpenEmailDialog : TimetableScreenNavIntent
    data object OnLogout : TimetableScreenNavIntent
    data object OnPreferences : TimetableScreenNavIntent
}

@Composable
private fun TimetableScreen(
    state: TimetableState,
    onIntent: (TimetableIntent) -> Unit,
    onNavIntent: (TimetableScreenNavIntent) -> Unit,
) {
    val year = state.year
    val month = state.month
    val breaks = state.breaks
    val cellData = state.cells
    val finder = state.finder
    val showSpotTimes = state.showSpotTimes
    val canEdit = state.canEdit

    // Localized month names. REMEMBERED against the language: this sits in the
    // screen's root scope, which re-executes on every state tick (each finder
    // keystroke, every busy flip) - without the remember it rebuilt 12 strings
    // and a list on each of them, for a value that only changes on a language
    // switch. localized() is the non-composable resolve; the language key is
    // what re-runs it after a switch.
    val language = LocalLanguage.current
    val monthNames = remember(language) {
        listOf(
            StringKey.MONTH_JANUARY.localized(), StringKey.MONTH_FEBRUARY.localized(),
            StringKey.MONTH_MARCH.localized(), StringKey.MONTH_APRIL.localized(),
            StringKey.MONTH_MAY.localized(), StringKey.MONTH_JUNE.localized(),
            StringKey.MONTH_JULY.localized(), StringKey.MONTH_AUGUST.localized(),
            StringKey.MONTH_SEPTEMBER.localized(), StringKey.MONTH_OCTOBER.localized(),
            StringKey.MONTH_NOVEMBER.localized(), StringKey.MONTH_DECEMBER.localized()
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        KeyboardEnabledHeader(
            state = state,
            monthName = monthNames[month - 1],
            onIntent = onIntent,
            onNavIntent = onNavIntent,
        )

        // Finder toolbar: Εύρεση opens the Details Console; the dropdown
        // switches among the selected contract's spots (what 'a' will add);
        // the X clears the finder (a fresh Εύρεση starts clean).
        if (canEdit) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = UIConst.paddingRegular, vertical = UIConst.paddingHairline)
            ) {
                AppButton(
                    text = Strings[StringKey.TIMETABLE_FINDER_BUTTON],
                    onClick = { onIntent(TimetableIntent.OpenFinder) },
                    variant = AppButtonVariant.SECONDARY,
                )
                Spacer(modifier = Modifier.width(UIConst.paddingSmall))
                var spotMenu by remember { mutableStateOf(false) }
                // Content-slot AppButton: dropdown anchor with custom content
                // (truncated spot description + caret).
                AppButton(
                    onClick = { spotMenu = true },
                    enabled = finder.spots.isNotEmpty()
                ) {
                    AppText(
                        finder.selectedSpot?.description?.take(48) ?: Strings[StringKey.TIMETABLE_NO_SPOT_SELECTED],
                        AppTextStyle.NOTE,
                        color = LocalContentColor.current,
                        maxLines = 1,
                    )
                    AppIcon(AppIcons.arrowDropDown)
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
                if (finder.selectedParty != null) {
                    AppIconButton(
                        label = Strings[StringKey.TIMETABLE_CD_CLEAR_FINDER],
                        icon = AppIcons.clear,
                        onClick = { onIntent(TimetableIntent.ClearFinder) },
                    )
                }
            }
        }
        if (state.showFinder) {
            SpotFinderDialog(
                finder = finder,
                onIntent = onIntent,
            )
        }

        // The scheduler grid with keyboard navigation (using LazyColumn for performance)
        LazySchedulerGrid(
            labels = schedulerLabels(),
            breaks = breaks,
            cellData = cellData,
            modifiedCells = state.modifiedCells,
            year = year,
            month = month,
            showTimes = showSpotTimes,
            modifier = Modifier
                .fillMaxSize()
                .padding(UIConst.paddingSmall),
            // The grid toolkit is a leaf - it can't read AppTheme.typography,
            // so we hand it the preference's raw factor. Cells and type grow
            // together; a bigger step trades days-per-screen for legibility.
            scale = AppTheme.fontSizeStep.factor,
            breakColumnWidth = 70.dp,
            dayColumnWidth = 42.dp,
            rowHeight = 28.dp,
            headerHeight = 50.dp,
            initialSelectedRow = state.selectedRow,
            initialSelectedColumn = state.selectedColumn,
            onSelectionChange = { row, col ->
                onIntent(TimetableIntent.SelectionChanged(row, col))
            },
            onCellClick = { _, _, _ ->
                // Single click - just select the cell (handled by grid)
            },
            onCellDoubleClick = { breakSlot, date, data ->
                // Double click or Enter key - navigate to detail screen
                onIntent(
                    TimetableIntent.OpenCell(
                        time = breakSlot.time,
                        date = date,
                        spotCount = data?.spotCount ?: 0,
                    )
                )
            },
            // 'a' persists the finder's selected spot as a placement; no
            // selected spot -> nothing happens. 'r' removes the most recent
            // placement this session added in the cell.
            onAddSpot = if (!canEdit) null else { breakSlot, date ->
                onIntent(TimetableIntent.AddSpotAt(breakSlot.time, date))
            },
            onDeleteSpot = if (!canEdit) null else { breakSlot, date ->
                onIntent(TimetableIntent.RemoveLastAt(breakSlot.time, date))
            },
            dailyTotals = state.dailyTotals,
            contextMenuItems = { breakSlot, date, data ->
                val spotCount = data?.spotCount ?: 0
                val key = SchedulerKey(breakSlot.time, date)

                buildList {
                    // Open/View details
                    add(ContextMenuEntry.Item(
                        label = StringKey.TIMETABLE_MENU_OPEN_DETAILS.localized(),
                        icon = { AppIcon(AppIcons.openInNew, size = AppIconSize.SMALL) },
                        shortcut = "Enter",
                        enabled = spotCount > 0
                    ) {
                        onIntent(
                            TimetableIntent.OpenCell(
                                time = breakSlot.time,
                                date = date,
                                spotCount = spotCount,
                            )
                        )
                    })

                    // Print the whole day this cell belongs to
                    add(ContextMenuEntry.Item(
                        label = StringKey.TIMETABLE_MENU_PRINT_DAY.localized().withArgs(listOf(dayMenuLabel(date))),
                        icon = { AppIcon(AppIcons.print, size = AppIconSize.SMALL) },
                        enabled = cellData.any { it.key.date == date && it.value.spotCount > 0 }
                    ) {
                        onIntent(TimetableIntent.PrintDay(date))
                    })

                    // Print this break's commercials
                    add(ContextMenuEntry.Item(
                        label = StringKey.TIMETABLE_MENU_PRINT_BREAK.localized(),
                        icon = { AppIcon(AppIcons.print, size = AppIconSize.SMALL) },
                        enabled = spotCount > 0
                    ) {
                        onIntent(TimetableIntent.PrintBreak(breakSlot.time, date))
                    })

                    // Legacy popup option: cells show spot counts or the
                    // summed spot times (05:42 for 342s). Persisted.
                    add(ContextMenuEntry.Separator)
                    add(ContextMenuEntry.Item(
                        label = (if (showSpotTimes) StringKey.TIMETABLE_MENU_SHOW_COUNTS else StringKey.TIMETABLE_MENU_SHOW_TIMES).localized(),
                        icon = {
                            AppIcon(
                                if (showSpotTimes) AppIcons.numbers else AppIcons.timer,
                                size = AppIconSize.SMALL,
                            )
                        }
                    ) {
                        onIntent(TimetableIntent.ToggleShowTimes)
                    })

                    // Editing actions - Normal User only; viewer roles get a
                    // read-and-print menu
                    if (canEdit) {
                        add(ContextMenuEntry.Separator)

                        // Edit submenu - same real add/remove the 'a'/'r'
                        // keys drive (add needs a spot armed via Εύρεση)
                        add(ContextMenuEntry.SubMenu(
                            label = StringKey.TIMETABLE_MENU_EDIT.localized(),
                            icon = { AppIcon(AppIcons.edit, size = AppIconSize.SMALL) },
                            items = listOf(
                                ContextMenuEntry.Item(
                                    label = finder.selectedSpot
                                        ?.let { StringKey.TIMETABLE_ADD_SPOT_NAMED.localized().withArgs(listOf(it.description.take(30))) }
                                        ?: StringKey.TIMETABLE_ADD_SPOT_HINT.localized(),
                                    icon = { AppIcon(AppIcons.add, size = AppIconSize.SMALL) },
                                    shortcut = "A",
                                    enabled = finder.selectedSpot != null
                                ) {
                                    onIntent(TimetableIntent.AddSpotAt(breakSlot.time, date))
                                },
                                ContextMenuEntry.Item(
                                    label = StringKey.TIMETABLE_MENU_REMOVE_LAST.localized(),
                                    icon = { AppIcon(AppIcons.delete, size = AppIconSize.SMALL) },
                                    shortcut = "R",
                                    enabled = (state.addedCounts[key] ?: 0) > 0
                                ) {
                                    onIntent(TimetableIntent.RemoveLastAt(breakSlot.time, date))
                                }
                            )
                        ))
                    }
                }
            },
            dayHeaderContextMenuItems = { date ->
                listOf(
                    ContextMenuEntry.Item(
                        label = StringKey.TIMETABLE_MENU_PRINT_DAY.localized().withArgs(listOf(dayMenuLabel(date))),
                        icon = { AppIcon(AppIcons.print, size = AppIconSize.SMALL) },
                        enabled = cellData.any { it.key.date == date && it.value.spotCount > 0 }
                    ) {
                        onIntent(TimetableIntent.PrintDay(date))
                    }
                )
            },
            breakHeaderContextMenuItems = { breakSlot ->
                val label = formatTime(breakSlot.time.hour, breakSlot.time.minute)

                listOf(
                    ContextMenuEntry.Item(
                        label = StringKey.TIMETABLE_MENU_PRINT_BREAK_MONTH.localized().withArgs(listOf(label)),
                        icon = { AppIcon(AppIcons.print, size = AppIconSize.SMALL) }
                    ) {
                        onIntent(TimetableIntent.PrintBreakMonth(breakSlot.time))
                    }
                )
            }
        )
    }
}

/** Menu label for a day, e.g. "05/12". */
private fun dayMenuLabel(date: LocalDate): String =
    "${date.day.toString().padStart(2, '0')}/${date.month.number.toString().padStart(2, '0')}"

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
                    AppIcons.arrowDropDown,
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
                                AppIcon(AppIcons.check, size = AppIconSize.SMALL)
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

@Composable
private fun KeyboardEnabledHeader(
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
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UIConst.paddingRegular, vertical = UIConst.paddingSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(UIConst.paddingRegular)
            ) {
                // Month navigation
                AppIconButton(
                    label = Strings[StringKey.TIMETABLE_CD_PREV_MONTH],
                    icon = AppIcons.arrowBack,
                    onClick = { onIntent(TimetableIntent.PreviousMonth) },
                )

                AppText(
                    "$monthName ${state.year}",
                    AppTextStyle.SCREEN_TITLE,
                    // 200dp is component geometry: a fixed title box so the
                    // header doesn't reflow between month-name lengths.
                    modifier = Modifier.width(200.dp)
                )

                AppIconButton(
                    label = Strings[StringKey.TIMETABLE_CD_NEXT_MONTH],
                    icon = AppIcons.arrowForward,
                    onClick = { onIntent(TimetableIntent.NextMonth) },
                )

                Spacer(modifier = Modifier.width(UIConst.paddingRegular))

                // "Προβολή κάθε: 1 Ώρα / Μισή Ώρα / Διάλειμμα" - the legacy
                // console's radio group. It chooses how much EMPTY scaffold is
                // drawn; the real breaks (the times spots actually aired) are in
                // every view, so no choice here can hide one.
                AppText(Strings[StringKey.TIMETABLE_VIEW_EVERY], AppTextStyle.TINY)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppRadioRow(
                        selected = state.viewMode == GridViewMode.HOURLY,
                        onClick = { onIntent(TimetableIntent.ViewModeChanged(GridViewMode.HOURLY)) },
                        label = Strings[StringKey.TIMETABLE_VIEW_HOURLY],
                    )
                    AppRadioRow(
                        selected = state.viewMode == GridViewMode.HALF_HOURLY,
                        onClick = { onIntent(TimetableIntent.ViewModeChanged(GridViewMode.HALF_HOURLY)) },
                        label = Strings[StringKey.TIMETABLE_VIEW_HALF_HOURLY],
                    )
                    AppRadioRow(
                        selected = state.viewMode == GridViewMode.CONDENSED,
                        onClick = { onIntent(TimetableIntent.ViewModeChanged(GridViewMode.CONDENSED)) },
                        label = Strings[StringKey.TIMETABLE_VIEW_BREAK],
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Keyboard shortcut hints
                Column {
                    AppText(
                        Strings[if (state.canEdit) StringKey.TIMETABLE_HINT_KEYS_EDIT else StringKey.TIMETABLE_HINT_KEYS_VIEW],
                        AppTextStyle.TINY,
                    )
                    AppText(
                        Strings[StringKey.TIMETABLE_HINT_CLICK_FOCUS],
                        AppTextStyle.TINY,
                    )
                }

                // Cells: spot count <-> summed spot time (persisted; the
                // icon shows the mode you'll switch TO)
                AppIconButton(
                    label = Strings[if (state.showSpotTimes) StringKey.TIMETABLE_CD_SHOW_COUNTS else StringKey.TIMETABLE_CD_SHOW_TIMES],
                    icon = if (state.showSpotTimes) AppIcons.numbers else AppIcons.timer,
                    onClick = { onIntent(TimetableIntent.ToggleShowTimes) },
                )

                // Station switcher (dropdown only when the user can access
                // more than one station)
                StationSelector(
                    stations = state.stations,
                    current = state.selectedStation,
                    onSelectStation = { onIntent(TimetableIntent.SelectStation(it)) },
                )

                // Email a party their schedule (staff action) - the dialog
                // belongs to :feature:schedule-email, so the app layer
                // renders it; this is just the launch point.
                if (state.canEdit) {
                    AppIconButton(
                        label = Strings[StringKey.TIMETABLE_CD_EMAIL_SCHEDULE],
                        icon = AppIcons.email,
                        onClick = { onNavIntent(TimetableScreenNavIntent.OnOpenEmailDialog) },
                    )
                }

                // Logged-in user: clicking the badge opens the account menu
                // (change password / recovery codes, or user management for
                // the super admin)
                AccountBadge(
                    displayName = state.displayName,
                    isAdmin = state.isAdmin,
                    role = state.role,
                )
                AppIconButton(
                    label = Strings[StringKey.TIMETABLE_CD_PREFERENCES],
                    icon = AppIcons.settings,
                    onClick = { onNavIntent(TimetableScreenNavIntent.OnPreferences) },
                )
                AppIconButton(
                    label = Strings[StringKey.TIMETABLE_CD_LOGOUT],
                    icon = AppIcons.logout,
                    onClick = { onNavIntent(TimetableScreenNavIntent.OnLogout) },
                    tint = MaterialTheme.colorScheme.error,
                )
            }

            // Report toolbar - Preview/Print/Export cover the entire month.
            // The ViewModel builds the payloads, runs the service and reports
            // the outcome through the app's global snackbar.
            ReportToolbar(
                onPreview = { onIntent(TimetableIntent.PreviewMonth) },
                onPrint = { onIntent(TimetableIntent.PrintMonth) },
                onExportPdf = { onIntent(TimetableIntent.ExportMonthPdf) },
                busy = state.reportBusy,
                available = state.reportsAvailable,
                labels = reportToolbarLabels(),
                metrics = reportToolbarMetrics(),
                modifier = Modifier.padding(horizontal = UIConst.paddingRegular, vertical = UIConst.paddingExtraSmall)
            )
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
        AppText(displayName, AppTextStyle.BODY_STRONG)
        AppText(
            if (isAdmin) Strings[StringKey.ROLE_SUPER_ADMIN] else Strings[role.toStringKey()],
            AppTextStyle.TINY,
        )
    }
}

// ═══ Εύρεση: the Details Console dialog (screen-private) ═══════════════

/**
 * The legacy "Εύρεση" Details Console, kept close to the original layout:
 * three stacked table sections - ΠΕΛΑΤΗΣ (search + the matches as a table
 * with Κωδικός/Επωνυμία/ΑΦΜ/Τηλέφωνο), ΣΥΜΒΟΛΑΙΑ ΠΕΛΑΤΗ (the contracts'
 * product lines; ERP product identity pending), ΜΗΝΥΜΑΤΑ (the line's spots
 * with Χρόνος/Αναλωμένα Spots/Secs) - and Επιλογή/Άκυρο bottom-right.
 * Stateless: renders [FinderUiState], dispatches [TimetableIntent]s (the
 * debounce lives in the ViewModel). "Επιλογή" arms the grid's 'a' key.
 */
@Composable
private fun SpotFinderDialog(
    finder: FinderUiState,
    onIntent: (TimetableIntent) -> Unit,
) {
    AppPopup(
        onDismissRequest = { onIntent(TimetableIntent.CloseFinder) },
        modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f),
    ) {
        Column(Modifier.padding(UIConst.paddingCompact)) {
            AppText(Strings[StringKey.FINDER_CONSOLE_TITLE], AppTextStyle.ITEM_TITLE)

            // ═══ ΠΕΛΑΤΗΣ ═══════════════════════════════════════════
            SectionTitle(Strings[StringKey.FINDER_SECTION_CUSTOMER])
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppRadioRow(
                    selected = finder.kind == PartyKind.CUSTOMER,
                    onClick = { onIntent(TimetableIntent.FinderKindChanged(PartyKind.CUSTOMER)) },
                    label = Strings[StringKey.FINDER_TAB_CUSTOMERS],
                )
                Spacer(Modifier.width(UIConst.paddingCompact))
                AppRadioRow(
                    selected = finder.kind == PartyKind.TRADER,
                    onClick = { onIntent(TimetableIntent.FinderKindChanged(PartyKind.TRADER)) },
                    label = Strings[StringKey.FINDER_TAB_ADVERTISERS],
                )
                Spacer(Modifier.width(UIConst.paddingRegular))
                AppTextField(
                    value = finder.query,
                    onValueChange = { onIntent(TimetableIntent.FinderQueryChanged(it)) },
                    label = Strings[StringKey.FINDER_SEARCH_LABEL],
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        if (finder.searching) {
                            AppSpinner()
                        }
                    }
                )
            }
            HeaderRow(
                Strings[StringKey.FINDER_COL_CODE] to 0.14f, Strings[StringKey.FINDER_COL_NAME] to 0.44f,
                Strings[StringKey.FINDER_COL_VAT] to 0.14f, Strings[StringKey.FINDER_COL_PHONE] to 0.14f, Strings[StringKey.FINDER_COL_SPOTS] to 0.14f,
            )
            // While a search runs the matches fill the table; the
            // chosen party stays pinned as its single (highlighted) row.
            val partyRows = finder.results.ifEmpty { listOfNotNull(finder.selectedParty) }
            LazyColumn(Modifier.fillMaxWidth().weight(0.24f)) {
                items(partyRows, key = { it.code }) { c ->
                    val isSel = c.code == finder.selectedParty?.code && finder.results.isEmpty()
                    TableRow(
                        selected = isSel,
                        onClick = { onIntent(TimetableIntent.FinderPartySelected(c)) },
                        c.code to 0.14f,
                        c.name to 0.44f,
                        (c.vatNumber ?: "") to 0.14f,
                        (c.phone ?: "") to 0.14f,
                        "${c.spotCount}" to 0.14f,
                    )
                }
            }

            // ═══ ΣΥΜΒΟΛΑΙΑ ΠΕΛΑΤΗ ══════════════════════════════════
            SectionTitle(Strings[StringKey.FINDER_SECTION_CONTRACTS])
            HeaderRow(
                Strings[StringKey.FINDER_COL_CONTRACT] to 0.16f, Strings[StringKey.FINDER_COL_LINE] to 0.06f, Strings[StringKey.FINDER_COL_DESCRIPTION] to 0.34f,
                Strings[StringKey.FINDER_COL_SPOTS_BOUGHT] to 0.12f, Strings[StringKey.FINDER_COL_SECS_BOUGHT] to 0.12f, Strings[StringKey.FINDER_COL_ISSUE_DATE] to 0.20f,
            )
            if (finder.loadingLines) {
                AppSpinner()
            }
            LazyColumn(Modifier.fillMaxWidth().weight(0.3f)) {
                items(finder.lines, key = { it.lineId }) { line ->
                    TableRow(
                        selected = line.lineId == finder.selectedLine?.lineId,
                        onClick = { onIntent(TimetableIntent.FinderLineSelected(line)) },
                        line.contractNumber to 0.16f,
                        "${line.lineNo}" to 0.06f,
                        (if (line.isGift) Strings[StringKey.FINDER_GIFT_LINE] else Strings[StringKey.FINDER_ERP_PENDING]) to 0.34f,
                        "${line.placements}" to 0.12f,
                        "${line.totalSeconds}" to 0.12f,
                        (line.entryDate ?: "") to 0.20f,
                    )
                }
            }

            // ═══ ΜΗΝΥΜΑΤΑ ══════════════════════════════════════════
            SectionTitle(Strings[StringKey.FINDER_SECTION_MESSAGES])
            HeaderRow(
                Strings[StringKey.FINDER_COL_MSG_DESCRIPTION] to 0.52f, Strings[StringKey.FINDER_COL_DURATION] to 0.16f,
                Strings[StringKey.FINDER_COL_USED_SPOTS] to 0.16f, Strings[StringKey.FINDER_COL_USED_SECS] to 0.16f,
            )
            if (finder.loadingSpots) {
                AppSpinner()
            }
            LazyColumn(Modifier.fillMaxWidth().weight(0.3f)) {
                items(finder.spots, key = { it.spotId }) { spot ->
                    TableRow(
                        selected = spot.spotId == finder.selectedSpot?.spotId,
                        onClick = { onIntent(TimetableIntent.FinderSpotSelected(spot)) },
                        spot.description to 0.52f,
                        "${spot.durationSeconds}" to 0.16f,
                        "${spot.placements}" to 0.16f,
                        "${spot.totalSeconds}" to 0.16f,
                    )
                }
            }

            // ═══ Επιλογή / Άκυρο (bottom-right, like the original) ══
            Row(
                Modifier.fillMaxWidth().padding(top = UIConst.paddingSmall),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppButton(
                    text = Strings[StringKey.FINDER_CLEAR],
                    onClick = { onIntent(TimetableIntent.ClearFinder) },
                    variant = AppButtonVariant.TEXT,
                )
                Spacer(Modifier.weight(1f))
                // Επιλογή is the dialog's primary action - PRIMARY keeps
                // the emphasis the old BUTTON_STRONG TextButton carried.
                AppButton(
                    text = Strings[StringKey.FINDER_SELECT],
                    onClick = { onIntent(TimetableIntent.CloseFinder) },
                    enabled = finder.selectedSpot != null,
                )
                AppButton(
                    text = Strings[StringKey.COMMON_CANCEL],
                    onClick = { onIntent(TimetableIntent.CloseFinder) },
                    variant = AppButtonVariant.TEXT,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    AppText(
        text, AppTextStyle.TABLE_HEADER,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = UIConst.paddingSmall, bottom = UIConst.paddingHairline)
    )
}

@Composable
private fun HeaderRow(vararg columns: Pair<String, Float>) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        for ((label, weight) in columns) {
            AppText(
                label, AppTextStyle.TABLE_HEADER,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(weight)
            )
        }
    }
}

@Composable
private fun TableRow(
    selected: Boolean,
    onClick: () -> Unit,
    vararg cells: Pair<String, Float>,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for ((value, weight) in cells) {
            AppText(
                value,
                if (selected) AppTextStyle.TABLE_CELL_STRONG else AppTextStyle.TABLE_CELL,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(weight)
            )
        }
    }
}


/**
 * Localized labels for the standalone grid toolkit (leaf takes no StringKey).
 * Remembered against the language: it is called from the screen's root scope,
 * so without it every state tick rebuilt the labels and their day map.
 */
@Composable
private fun schedulerLabels(): SchedulerLabels {
    val language = LocalLanguage.current
    return remember(language) {
        SchedulerLabels(
            timeDay = StringKey.TIMETABLE_TIME_DAY.localized(),
            totals = StringKey.TIMETABLE_TOTALS.localized(),
            dayAbbreviations = mapOf(
                DayOfWeek.MONDAY to StringKey.DAY_SHORT_MONDAY.localized(),
                DayOfWeek.TUESDAY to StringKey.DAY_SHORT_TUESDAY.localized(),
                DayOfWeek.WEDNESDAY to StringKey.DAY_SHORT_WEDNESDAY.localized(),
                DayOfWeek.THURSDAY to StringKey.DAY_SHORT_THURSDAY.localized(),
                DayOfWeek.FRIDAY to StringKey.DAY_SHORT_FRIDAY.localized(),
                DayOfWeek.SATURDAY to StringKey.DAY_SHORT_SATURDAY.localized(),
                DayOfWeek.SUNDAY to StringKey.DAY_SHORT_SUNDAY.localized(),
            ),
        )
    }
}

/**
 * Localized BUTTON labels for the standalone report toolbar. The outcome
 * strings (no spots / cancelled / PDF saved) belong to the ViewModel now -
 * they travel to the global snackbar, not to this component.
 */

// ═══ Previews ══════════════════════════════════════════════════════════
//
// Two months, because the grid's two shapes are genuinely different code
// paths: a month with real breaks and cells, and a month where nothing aired
// (the view's empty scaffold and an empty totals row - the shape a single
// happy-path preview would never show).

/** The station whose data is on screen (one grant - a plain label, no dropdown). */
private val previewStation = StationAccess(
    id = "crete-tv",
    name = "Crete TV",
    role = AppRole.NORMAL_USER.name,
)

/**
 * A busy month's rows. The zone colours are DATA (the legacy console's own),
 * never theme-adapted - so they are literals here, exactly as the server sends
 * them.
 */
private val previewBreaks = persistentListOf(
    BreakSlot(LocalTime(9, 30), "09:30", Color(0xFFBBDEFB), BreakZone.STANDARD),
    BreakSlot(LocalTime(12, 0), "12:00", Color(0xFFBBDEFB), BreakZone.STANDARD),
    BreakSlot(LocalTime(15, 30), "15:30", Color(0xFFC8E6C9), BreakZone.SPECIAL),
    BreakSlot(LocalTime(20, 30), "20:30", Color(0xFFF8BBD0), BreakZone.PRIME),
    BreakSlot(LocalTime(21, 45), "21:45", Color(0xFFF8BBD0), BreakZone.PRIME),
    BreakSlot(LocalTime(23, 0), "23:00"),
)

/** July 2026 on Crete TV: most cells busy, some breaks quiet on some days. */
private fun previewCells(): ImmutableMap<SchedulerKey, SchedulerCellData> {
    val programs = listOf(
        "Morning Edition", "Midday News", "Crete Today",
        "Evening News", "Late Movie", "Night Music",
    )
    return buildMap {
        previewBreaks.forEachIndexed { row, slot ->
            for (day in 1..31) {
                val spots = (day + row * 2) % 7
                if (spots == 0) continue        // a quiet break leaves the cell empty
                put(
                    SchedulerKey(slot.time, LocalDate(2026, 7, day)),
                    SchedulerCellData(
                        spotCount = spots,
                        totalDurationSeconds = spots * 31,
                        zoneColor = slot.zoneColor,
                        programName = programs[row],
                    ),
                )
            }
        }
    }.toImmutableMap()
}

/** A populated month: real breaks, cells, daily totals, an editing user. */
@Preview
@Composable
private fun TimetableScreenPreview() {
    val cells = previewCells()
    AppPreview(padded = false) {
        TimetableScreen(
            state = TimetableState(
                year = 2026,
                month = 7,
                breaks = previewBreaks,
                viewMode = GridViewMode.CONDENSED,
                cells = cells,
                dailyTotals = calculateDailyTotals(cells).toImmutableMap(),
                selectedRow = 3,
                selectedColumn = 2,
                reportsAvailable = true,
                canEdit = true,
                displayName = "Maria Nikolaou",
                role = AppRole.NORMAL_USER,
                stations = persistentListOf(previewStation),
                selectedStation = previewStation,
            ),
            onIntent = {},
            onNavIntent = {},
        )
    }
}

/**
 * A month nothing aired in. There are no real breaks to draw, so the grid is
 * only the view's hourly scaffold - empty rows, no cells, an empty totals row.
 */
@Preview
@Composable
private fun TimetableScreenEmptyMonthPreview() = AppPreview(padded = false) {
    TimetableScreen(
        state = TimetableState(
            year = 2026,
            month = 8,
            breaks = (6..23)
                .map { hour -> BreakSlot(LocalTime(hour, 0), formatTime(hour, 0)) }
                .toImmutableList(),
            viewMode = GridViewMode.HOURLY,
            cells = persistentMapOf(),
            dailyTotals = persistentMapOf(),
            reportsAvailable = true,
            canEdit = true,
            displayName = "Maria Nikolaou",
            role = AppRole.NORMAL_USER,
            stations = persistentListOf(previewStation),
            selectedStation = previewStation,
        ),
        onIntent = {},
        onNavIntent = {},
    )
}

