package eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable

import kotlinx.datetime.DayOfWeek
import eu.anifantakis.commercials.reports.ui.ReportToolbarLabels
import eu.anifantakis.commercials.core.presentation.grids.SchedulerLabels
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.core.presentation.util.toStringKey
import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.core.domain.auth.StationAccess
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.presentation.helper.ObserveEffects
import eu.anifantakis.commercials.core.presentation.grids.BreakSlot
import eu.anifantakis.commercials.core.presentation.grids.ContextMenuEntry
import eu.anifantakis.commercials.core.presentation.grids.LazySchedulerGrid
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.core.presentation.grids.formatTime
import eu.anifantakis.commercials.reports.ui.ReportToolbar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.datetime.LocalDate

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
    onOpenDetail: (breakId: Long, date: LocalDate) -> Unit,
    onOpenEmailDialog: () -> Unit,
    onLogout: () -> Unit,
    onPreferences: () -> Unit,
) {
    ObserveEffects(viewModel.events) { effect ->
        when (effect) {
            is TimetableEffect.OpenDetail -> onOpenDetail(effect.breakId, effect.date)
        }
    }

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

    // Localized month names (Strings[] — recompose on language switch)
    val monthNames = listOf(
        Strings[StringKey.MONTH_JANUARY], Strings[StringKey.MONTH_FEBRUARY],
        Strings[StringKey.MONTH_MARCH], Strings[StringKey.MONTH_APRIL],
        Strings[StringKey.MONTH_MAY], Strings[StringKey.MONTH_JUNE],
        Strings[StringKey.MONTH_JULY], Strings[StringKey.MONTH_AUGUST],
        Strings[StringKey.MONTH_SEPTEMBER], Strings[StringKey.MONTH_OCTOBER],
        Strings[StringKey.MONTH_NOVEMBER], Strings[StringKey.MONTH_DECEMBER]
    )

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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                OutlinedButton(onClick = { onIntent(TimetableIntent.OpenFinder) }) { AppText(Strings[StringKey.TIMETABLE_FINDER_BUTTON], AppTextStyle.BUTTON) }
                Spacer(modifier = Modifier.width(8.dp))
                var spotMenu by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { spotMenu = true },
                    enabled = finder.spots.isNotEmpty()
                ) {
                    AppText(
                        finder.selectedSpot?.description?.take(48) ?: Strings[StringKey.TIMETABLE_NO_SPOT_SELECTED],
                        AppTextStyle.NOTE,
                        color = LocalContentColor.current,
                        maxLines = 1,
                    )
                    Icon(AppIcons.arrowDropDown, contentDescription = null)
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
                    IconButton(onClick = { onIntent(TimetableIntent.ClearFinder) }) {
                        Icon(AppIcons.clear, contentDescription = Strings[StringKey.TIMETABLE_CD_CLEAR_FINDER])
                    }
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
                .padding(8.dp),
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
                        breakId = breakSlot.id,
                        date = date,
                        spotCount = data?.spotCount ?: 0,
                    )
                )
            },
            // 'a' persists the finder's selected spot as a placement; no
            // selected spot -> nothing happens. 'r' removes the most recent
            // placement this session added in the cell.
            onAddSpot = if (!canEdit) null else { breakSlot, date ->
                onIntent(TimetableIntent.AddSpotAt(breakSlot.id, date))
            },
            onDeleteSpot = if (!canEdit) null else { breakSlot, date ->
                onIntent(TimetableIntent.RemoveLastAt(breakSlot.id, date))
            },
            dailyTotals = state.dailyTotals,
            contextMenuItems = { breakSlot, date, data ->
                val spotCount = data?.spotCount ?: 0
                val key = SchedulerKey(breakSlot.id, date)

                buildList {
                    // Open/View details
                    add(ContextMenuEntry.Item(
                        label = StringKey.TIMETABLE_MENU_OPEN_DETAILS.localized(),
                        icon = { Icon(AppIcons.openInNew, null, modifier = Modifier.size(16.dp)) },
                        shortcut = "Enter",
                        enabled = spotCount > 0
                    ) {
                        onIntent(
                            TimetableIntent.OpenCell(
                                breakId = breakSlot.id,
                                date = date,
                                spotCount = spotCount,
                            )
                        )
                    })

                    // Print the whole day this cell belongs to
                    add(ContextMenuEntry.Item(
                        label = StringKey.TIMETABLE_MENU_PRINT_DAY.localized().withArgs(listOf(dayMenuLabel(date))),
                        icon = { Icon(AppIcons.print, null, modifier = Modifier.size(16.dp)) },
                        enabled = cellData.any { it.key.date == date && it.value.spotCount > 0 }
                    ) {
                        onIntent(TimetableIntent.PrintDay(date))
                    })

                    // Print this break's commercials
                    add(ContextMenuEntry.Item(
                        label = StringKey.TIMETABLE_MENU_PRINT_BREAK.localized(),
                        icon = { Icon(AppIcons.print, null, modifier = Modifier.size(16.dp)) },
                        enabled = spotCount > 0
                    ) {
                        onIntent(TimetableIntent.PrintBreak(breakSlot.id, date))
                    })

                    // Legacy popup option: cells show spot counts or the
                    // summed spot times (05:42 for 342s). Persisted.
                    add(ContextMenuEntry.Separator)
                    add(ContextMenuEntry.Item(
                        label = (if (showSpotTimes) StringKey.TIMETABLE_MENU_SHOW_COUNTS else StringKey.TIMETABLE_MENU_SHOW_TIMES).localized(),
                        icon = {
                            Icon(
                                if (showSpotTimes) AppIcons.numbers else AppIcons.timer,
                                null,
                                modifier = Modifier.size(16.dp)
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
                            icon = { Icon(AppIcons.edit, null, modifier = Modifier.size(16.dp)) },
                            items = listOf(
                                ContextMenuEntry.Item(
                                    label = finder.selectedSpot
                                        ?.let { StringKey.TIMETABLE_ADD_SPOT_NAMED.localized().withArgs(listOf(it.description.take(30))) }
                                        ?: StringKey.TIMETABLE_ADD_SPOT_HINT.localized(),
                                    icon = { Icon(AppIcons.add, null, modifier = Modifier.size(16.dp)) },
                                    shortcut = "A",
                                    enabled = finder.selectedSpot != null
                                ) {
                                    onIntent(TimetableIntent.AddSpotAt(breakSlot.id, date))
                                },
                                ContextMenuEntry.Item(
                                    label = StringKey.TIMETABLE_MENU_REMOVE_LAST.localized(),
                                    icon = { Icon(AppIcons.delete, null, modifier = Modifier.size(16.dp)) },
                                    shortcut = "R",
                                    enabled = (state.addedCounts[key] ?: 0) > 0
                                ) {
                                    onIntent(TimetableIntent.RemoveLastAt(breakSlot.id, date))
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
                        icon = { Icon(AppIcons.print, null, modifier = Modifier.size(16.dp)) },
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
                        icon = { Icon(AppIcons.print, null, modifier = Modifier.size(16.dp)) }
                    ) {
                        onIntent(TimetableIntent.PrintBreakMonth(breakSlot.id))
                    }
                )
            }
        )
    }
}

/** Menu label for a day, e.g. "05/12". */
private fun dayMenuLabel(date: LocalDate): String =
    "${date.day.toString().padStart(2, '0')}/${date.monthNumber.toString().padStart(2, '0')}"

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
            TextButton(onClick = { expanded = true }) {
                AppText(
                    current.name,
                    AppTextStyle.SECTION_TITLE,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
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
                                Icon(AppIcons.check, contentDescription = null, modifier = Modifier.size(16.dp))
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
                modifier = Modifier.padding(horizontal = 8.dp)
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Month navigation
                IconButton(onClick = { onIntent(TimetableIntent.PreviousMonth) }) {
                    Icon(
                        AppIcons.arrowBack,
                        contentDescription = Strings[StringKey.TIMETABLE_CD_PREV_MONTH]
                    )
                }

                AppText(
                    "$monthName ${state.year}",
                    AppTextStyle.SCREEN_TITLE,
                    modifier = Modifier.width(200.dp)
                )

                IconButton(onClick = { onIntent(TimetableIntent.NextMonth) }) {
                    Icon(
                        AppIcons.arrowForward,
                        contentDescription = Strings[StringKey.TIMETABLE_CD_NEXT_MONTH]
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
                IconButton(onClick = { onIntent(TimetableIntent.ToggleShowTimes) }) {
                    Icon(
                        if (state.showSpotTimes) AppIcons.numbers else AppIcons.timer,
                        contentDescription = Strings[if (state.showSpotTimes) StringKey.TIMETABLE_CD_SHOW_COUNTS else StringKey.TIMETABLE_CD_SHOW_TIMES]
                    )
                }

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
                    IconButton(onClick = { onNavIntent(TimetableScreenNavIntent.OnOpenEmailDialog) }) {
                        Icon(AppIcons.email, contentDescription = Strings[StringKey.TIMETABLE_CD_EMAIL_SCHEDULE])
                    }
                }

                // Logged-in user: clicking the badge opens the account menu
                // (change password / recovery codes, or user management for
                // the super admin)
                AccountBadge(
                    displayName = state.displayName,
                    isAdmin = state.isAdmin,
                    role = state.role,
                )
                IconButton(onClick = { onNavIntent(TimetableScreenNavIntent.OnPreferences) }) {
                    Icon(
                        AppIcons.settings,
                        contentDescription = Strings[StringKey.TIMETABLE_CD_PREFERENCES]
                    )
                }
                IconButton(onClick = { onNavIntent(TimetableScreenNavIntent.OnLogout) }) {
                    Icon(
                        AppIcons.logout,
                        contentDescription = Strings[StringKey.TIMETABLE_CD_LOGOUT],
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Report toolbar - Preview/Print/Export cover the entire month
            ReportToolbar(
                year = state.year,
                month = state.month,
                labels = reportToolbarLabels(),
                breaks = state.breaks,
                cellData = state.cells,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
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
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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
    Dialog(
        onDismissRequest = { onIntent(TimetableIntent.CloseFinder) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(14.dp)) {
                AppText(Strings[StringKey.FINDER_CONSOLE_TITLE], AppTextStyle.ITEM_TITLE)

                // ═══ ΠΕΛΑΤΗΣ ═══════════════════════════════════════════
                SectionTitle(Strings[StringKey.FINDER_SECTION_CUSTOMER])
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = finder.kind == PartyKind.CUSTOMER,
                        onClick = { onIntent(TimetableIntent.FinderKindChanged(PartyKind.CUSTOMER)) }
                    )
                    AppText(
                        Strings[StringKey.FINDER_TAB_CUSTOMERS], AppTextStyle.BODY,
                        modifier = Modifier.clickable {
                            onIntent(TimetableIntent.FinderKindChanged(PartyKind.CUSTOMER))
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    RadioButton(
                        selected = finder.kind == PartyKind.TRADER,
                        onClick = { onIntent(TimetableIntent.FinderKindChanged(PartyKind.TRADER)) }
                    )
                    AppText(
                        Strings[StringKey.FINDER_TAB_ADVERTISERS], AppTextStyle.BODY,
                        modifier = Modifier.clickable {
                            onIntent(TimetableIntent.FinderKindChanged(PartyKind.TRADER))
                        }
                    )
                    Spacer(Modifier.width(16.dp))
                    OutlinedTextField(
                        value = finder.query,
                        onValueChange = { onIntent(TimetableIntent.FinderQueryChanged(it)) },
                        label = { AppText(Strings[StringKey.FINDER_SEARCH_LABEL], AppTextStyle.NOTE, color = LocalContentColor.current) },
                        singleLine = true, modifier = Modifier.weight(1f),
                        trailingIcon = {
                            if (finder.searching) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
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
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
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
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
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
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onIntent(TimetableIntent.ClearFinder) }) { AppText(Strings[StringKey.FINDER_CLEAR], AppTextStyle.BUTTON) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = finder.selectedSpot != null,
                        onClick = { onIntent(TimetableIntent.CloseFinder) }
                    ) {
                        AppText(Strings[StringKey.FINDER_SELECT], AppTextStyle.BUTTON_STRONG)
                    }
                    TextButton(onClick = { onIntent(TimetableIntent.CloseFinder) }) { AppText(Strings[StringKey.COMMON_CANCEL], AppTextStyle.BUTTON) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    AppText(
        text, AppTextStyle.TABLE_HEADER,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
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


/** Localized labels for the standalone grid toolkit (leaf takes no StringKey). */
@Composable
private fun schedulerLabels() = SchedulerLabels(
    timeDay = Strings[StringKey.TIMETABLE_TIME_DAY],
    totals = Strings[StringKey.TIMETABLE_TOTALS],
    dayAbbreviations = mapOf(
        DayOfWeek.MONDAY to Strings[StringKey.DAY_SHORT_MONDAY],
        DayOfWeek.TUESDAY to Strings[StringKey.DAY_SHORT_TUESDAY],
        DayOfWeek.WEDNESDAY to Strings[StringKey.DAY_SHORT_WEDNESDAY],
        DayOfWeek.THURSDAY to Strings[StringKey.DAY_SHORT_THURSDAY],
        DayOfWeek.FRIDAY to Strings[StringKey.DAY_SHORT_FRIDAY],
        DayOfWeek.SATURDAY to Strings[StringKey.DAY_SHORT_SATURDAY],
        DayOfWeek.SUNDAY to Strings[StringKey.DAY_SHORT_SUNDAY],
    ),
)

/** Localized labels for the standalone report toolbar. */
@Composable
private fun reportToolbarLabels() = ReportToolbarLabels(
    preview = Strings[StringKey.REPORT_PREVIEW],
    print = Strings[StringKey.REPORT_PRINT],
    exportPdf = Strings[StringKey.REPORT_EXPORT_PDF],
    noSpots = Strings[StringKey.REPORT_NO_SPOTS],
    pdfSavedPrefix = Strings[StringKey.REPORT_PDF_SAVED_PREFIX],
    cancelled = Strings[StringKey.REPORT_CANCELLED],
    notAvailable = Strings[StringKey.REPORT_NOT_AVAILABLE],
)
