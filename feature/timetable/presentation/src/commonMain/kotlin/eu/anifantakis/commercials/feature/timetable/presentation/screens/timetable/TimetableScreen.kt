package eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable

import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.presentation.helper.ObserveEffects
import eu.anifantakis.commercials.feature.timetable.presentation.mappers.calculateDailyTotals
import eu.anifantakis.commercials.core.presentation.grids.BreakSlot
import eu.anifantakis.commercials.core.presentation.grids.ContextMenuEntry
import eu.anifantakis.commercials.core.presentation.grids.LazySchedulerGrid
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import eu.anifantakis.commercials.core.presentation.grids.formatTime
import eu.anifantakis.commercials.reports.ReportDataFactory
import eu.anifantakis.commercials.reports.ReportPayload
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.models.ReportConfig
import eu.anifantakis.commercials.reports.print
import eu.anifantakis.commercials.reports.toReportPayload
import eu.anifantakis.commercials.reports.ui.ReportToolbar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject

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
    onOpenDetail: (breakId: Long, breakTime: String, date: LocalDate, spotCount: Int) -> Unit,
    onOpenEmailDialog: () -> Unit,
    onLogout: () -> Unit,
    onPreferences: () -> Unit,
) {
    ObserveEffects(viewModel.events) { effect ->
        when (effect) {
            is TimetableEffect.OpenDetail ->
                onOpenDetail(effect.breakId, effect.breakLabel, effect.date, effect.spotCount)
        }
    }

    // Session revision drives reloads: login, logout and station switches
    // must refetch with the new token/role and drop per-station edits.
    val authSession = koinInject<AuthSession>()
    val revision = authSession.revision
    var lastRevision by remember { mutableStateOf(revision) }
    LaunchedEffect(revision) {
        if (revision != lastRevision) {
            lastRevision = revision
            viewModel.onAction(TimetableIntent.Reload)
        }
    }
    val canEdit = authSession.role.canEdit

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
        canEdit = canEdit,
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
    canEdit: Boolean,
) {
    val year = state.year
    val month = state.month
    val breaks = state.breaks
    val cellData = state.cells
    val finder = state.finder
    val showSpotTimes = state.showSpotTimes

    // Daily totals for the Σύνολα footer, recomputed when cells change
    val dailyTotals = remember(cellData) { calculateDailyTotals(cellData).toImmutableMap() }

    // Greek month names
    val greekMonths = listOf(
        "Ιανουάριος", "Φεβρουάριος", "Μάρτιος", "Απρίλιος",
        "Μάιος", "Ιούνιος", "Ιούλιος", "Αύγουστος",
        "Σεπτέμβριος", "Οκτώβριος", "Νοέμβριος", "Δεκέμβριος"
    )

    // Report printing from popup menus (day header, break header, cell)
    val reportScope = rememberCoroutineScope()
    val reportService = koinInject<ReportService>()

    fun printDay(date: LocalDate) {
        reportScope.launch {
            val data = ReportDataFactory.createProgramFlowData(date, breaks, cellData)
            if (data.items.isNotEmpty()) {
                reportService.print(data.toReportPayload(ReportConfig()))
            }
        }
    }

    fun printBreak(breakSlot: BreakSlot, date: LocalDate) {
        reportScope.launch {
            val commercials = cellData[SchedulerKey(breakSlot.id, date)]?.commercials ?: return@launch
            val data = ReportDataFactory.createBreakProgramFlowData(
                date = date,
                breakTimeLabel = formatTime(breakSlot.time.hour, breakSlot.time.minute),
                commercials = commercials
            )
            if (data.items.isNotEmpty()) {
                reportService.print(data.toReportPayload(ReportConfig()))
            }
        }
    }

    fun printBreakForMonth(breakSlot: BreakSlot) {
        reportScope.launch {
            val payloads: List<ReportPayload> = ReportDataFactory
                .createMonthProgramFlowData(year, month, listOf(breakSlot), cellData)
                .map { it.toReportPayload(ReportConfig()) }
            if (payloads.isNotEmpty()) {
                reportService.print(payloads)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        KeyboardEnabledHeader(
            year = year,
            month = month,
            monthName = greekMonths[month - 1],
            breaks = breaks,
            cellData = cellData,
            canEdit = canEdit,
            showSpotTimes = showSpotTimes,
            onToggleShowTimes = { onIntent(TimetableIntent.ToggleShowTimes) },
            onEmail = { onNavIntent(TimetableScreenNavIntent.OnOpenEmailDialog) },
            onLogout = { onNavIntent(TimetableScreenNavIntent.OnLogout) },
            onPreferences = { onNavIntent(TimetableScreenNavIntent.OnPreferences) },
            onPreviousMonth = { onIntent(TimetableIntent.PreviousMonth) },
            onNextMonth = { onIntent(TimetableIntent.NextMonth) },
        )

        // Finder toolbar: Εύρεση opens the Details Console; the dropdown
        // switches among the selected contract's spots (what 'a' will add);
        // the X clears the finder (a fresh Εύρεση starts clean).
        if (canEdit) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                OutlinedButton(onClick = { onIntent(TimetableIntent.OpenFinder) }) { Text("Εύρεση") }
                Spacer(modifier = Modifier.width(8.dp))
                var spotMenu by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { spotMenu = true },
                    enabled = finder.spots.isNotEmpty()
                ) {
                    Text(
                        finder.selectedSpot?.description?.take(48) ?: "— κανένα σποτ —",
                        maxLines = 1, fontSize = 12.sp
                    )
                    Icon(AppIcons.arrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = spotMenu, onDismissRequest = { spotMenu = false }) {
                    finder.spots.forEach { spot ->
                        DropdownMenuItem(
                            text = { Text("${spot.description} (${spot.durationSeconds}″)", fontSize = 12.sp) },
                            onClick = {
                                onIntent(TimetableIntent.FinderSpotSelected(spot))
                                spotMenu = false
                            }
                        )
                    }
                }
                if (finder.selectedParty != null) {
                    IconButton(onClick = { onIntent(TimetableIntent.ClearFinder) }) {
                        Icon(AppIcons.clear, contentDescription = "Καθαρισμός εύρεσης")
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
            breaks = breaks,
            cellData = cellData,
            modifiedCells = state.modifiedCells,
            year = year,
            month = month,
            showTimes = showSpotTimes,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
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
                        breakLabel = formatTime(breakSlot.time.hour, breakSlot.time.minute),
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
            dailyTotals = dailyTotals,
            contextMenuItems = { breakSlot, date, data ->
                val spotCount = data?.spotCount ?: 0
                val key = SchedulerKey(breakSlot.id, date)

                buildList {
                    // Open/View details
                    add(ContextMenuEntry.Item(
                        label = "Open Details",
                        icon = { Icon(AppIcons.openInNew, null, modifier = Modifier.size(16.dp)) },
                        shortcut = "Enter",
                        enabled = spotCount > 0
                    ) {
                        onIntent(
                            TimetableIntent.OpenCell(
                                breakId = breakSlot.id,
                                breakLabel = formatTime(breakSlot.time.hour, breakSlot.time.minute),
                                date = date,
                                spotCount = spotCount,
                            )
                        )
                    })

                    // Print the whole day this cell belongs to
                    add(ContextMenuEntry.Item(
                        label = "Print Day ${dayMenuLabel(date)}",
                        icon = { Icon(AppIcons.print, null, modifier = Modifier.size(16.dp)) },
                        enabled = cellData.any { it.key.date == date && it.value.spotCount > 0 }
                    ) {
                        printDay(date)
                    })

                    // Print this break's commercials
                    add(ContextMenuEntry.Item(
                        label = "Print Break",
                        icon = { Icon(AppIcons.print, null, modifier = Modifier.size(16.dp)) },
                        enabled = spotCount > 0
                    ) {
                        printBreak(breakSlot, date)
                    })

                    // Legacy popup option: cells show spot counts or the
                    // summed spot times (05:42 for 342s). Persisted.
                    add(ContextMenuEntry.Separator)
                    add(ContextMenuEntry.Item(
                        label = if (showSpotTimes) "Show Spot Counts" else "Show Spot Times",
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
                            label = "Edit",
                            icon = { Icon(AppIcons.edit, null, modifier = Modifier.size(16.dp)) },
                            items = listOf(
                                ContextMenuEntry.Item(
                                    label = finder.selectedSpot
                                        ?.let { "Add «${it.description.take(30)}»" }
                                        ?: "Add Spot (επιλέξτε από Εύρεση)",
                                    icon = { Icon(AppIcons.add, null, modifier = Modifier.size(16.dp)) },
                                    shortcut = "A",
                                    enabled = finder.selectedSpot != null
                                ) {
                                    onIntent(TimetableIntent.AddSpotAt(breakSlot.id, date))
                                },
                                ContextMenuEntry.Item(
                                    label = "Remove Last Added",
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
                        label = "Print Day ${dayMenuLabel(date)}",
                        icon = { Icon(AppIcons.print, null, modifier = Modifier.size(16.dp)) },
                        enabled = cellData.any { it.key.date == date && it.value.spotCount > 0 }
                    ) {
                        printDay(date)
                    }
                )
            },
            breakHeaderContextMenuItems = { breakSlot ->
                val label = formatTime(breakSlot.time.hour, breakSlot.time.minute)

                listOf(
                    ContextMenuEntry.Item(
                        label = "Print Break $label (Entire Month)",
                        icon = { Icon(AppIcons.print, null, modifier = Modifier.size(16.dp)) }
                    ) {
                        printBreakForMonth(breakSlot)
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
 * label; with several it becomes a dropdown - selecting one switches the
 * whole app to that station's schema (data refetch + role re-evaluation are
 * driven by the session revision bump in [AuthSession.selectStation]).
 */
@Composable
private fun StationSelector(authSession: AuthSession) {
    val current = authSession.selectedStation ?: return
    var expanded by remember { mutableStateOf(false) }

    Box {
        if (authSession.stations.size > 1) {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = current.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    AppIcons.arrowDropDown,
                    contentDescription = "Switch station",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                authSession.stations.forEach { station ->
                    DropdownMenuItem(
                        text = { Text(station.name) },
                        leadingIcon = {
                            if (station.id == current.id) {
                                Icon(AppIcons.check, contentDescription = null, modifier = Modifier.size(16.dp))
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                        },
                        onClick = {
                            expanded = false
                            authSession.selectStation(station.id)
                        }
                    )
                }
            }
        } else {
            Text(
                text = current.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun KeyboardEnabledHeader(
    year: Int,
    month: Int,
    monthName: String,
    breaks: ImmutableList<BreakSlot>,
    cellData: ImmutableMap<SchedulerKey, SchedulerCellData>,
    canEdit: Boolean,
    showSpotTimes: Boolean,
    onToggleShowTimes: () -> Unit,
    onEmail: () -> Unit,
    onLogout: () -> Unit,
    onPreferences: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
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
                IconButton(onClick = onPreviousMonth) {
                    Icon(
                        AppIcons.arrowBack,
                        contentDescription = "Previous month"
                    )
                }

                Text(
                    text = "$monthName $year",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    modifier = Modifier.width(200.dp)
                )

                IconButton(onClick = onNextMonth) {
                    Icon(
                        AppIcons.arrowForward,
                        contentDescription = "Next month"
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Keyboard shortcut hints
                Column {
                    Text(
                        text = if (canEdit) "Arrows: Navigate | Enter: Open | A: Add | R: Remove"
                        else "Arrows: Navigate | Enter: Open (view only)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Click grid to focus, then use keyboard",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Cells: spot count <-> summed spot time (persisted; the
                // icon shows the mode you'll switch TO)
                IconButton(onClick = onToggleShowTimes) {
                    Icon(
                        if (showSpotTimes) AppIcons.numbers else AppIcons.timer,
                        contentDescription = if (showSpotTimes) "Show spot counts" else "Show spot times"
                    )
                }

                // Station switcher (dropdown only when the user can access
                // more than one station)
                val authSession = koinInject<AuthSession>()
                @Suppress("UNUSED_EXPRESSION") authSession.revision
                StationSelector(authSession)

                // Email a party their schedule (staff action) - the dialog
                // belongs to :feature:schedule-email, so the app layer
                // renders it; this is just the launch point.
                if (canEdit) {
                    IconButton(onClick = onEmail) {
                        Icon(AppIcons.email, contentDescription = "Email customer schedule")
                    }
                }

                // Logged-in user: clicking the badge opens the account menu
                // (change password / recovery codes, or user management for
                // the super admin)
                AccountBadge(authSession)
                IconButton(onClick = onPreferences) {
                    Icon(
                        AppIcons.settings,
                        contentDescription = "Preferences"
                    )
                }
                IconButton(onClick = onLogout) {
                    Icon(
                        AppIcons.logout,
                        contentDescription = "Logout",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Report toolbar - Preview/Print/Export cover the entire month
            ReportToolbar(
                year = year,
                month = month,
                breaks = breaks,
                cellData = cellData,
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
private fun AccountBadge(authSession: AuthSession) {
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = authSession.displayName,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (authSession.isAdmin) "Super Administrator" else authSession.role.label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                Text("Εύρεση — Κονσόλα Λεπτομερειών", fontSize = 15.sp, fontWeight = FontWeight.Bold)

                // ═══ ΠΕΛΑΤΗΣ ═══════════════════════════════════════════
                SectionTitle("Πελάτης")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = finder.kind == PartyKind.CUSTOMER,
                        onClick = { onIntent(TimetableIntent.FinderKindChanged(PartyKind.CUSTOMER)) }
                    )
                    Text(
                        "Πελάτες", fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            onIntent(TimetableIntent.FinderKindChanged(PartyKind.CUSTOMER))
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    RadioButton(
                        selected = finder.kind == PartyKind.TRADER,
                        onClick = { onIntent(TimetableIntent.FinderKindChanged(PartyKind.TRADER)) }
                    )
                    Text(
                        "Διαφημιστές", fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            onIntent(TimetableIntent.FinderKindChanged(PartyKind.TRADER))
                        }
                    )
                    Spacer(Modifier.width(16.dp))
                    OutlinedTextField(
                        value = finder.query,
                        onValueChange = { onIntent(TimetableIntent.FinderQueryChanged(it)) },
                        label = { Text("Εύρεση (3+ χαρακτήρες)", fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.weight(1f),
                        trailingIcon = {
                            if (finder.searching) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            }
                        }
                    )
                }
                HeaderRow(
                    "Κωδικός" to 0.14f, "Επωνυμία" to 0.44f,
                    "ΑΦΜ" to 0.14f, "Τηλέφωνο" to 0.14f, "Σποτ" to 0.14f,
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
                SectionTitle("Συμβόλαια Πελάτη — προϊόντα (απεικόνιση έως το ERP import)")
                HeaderRow(
                    "Συμβ." to 0.16f, "Γρ." to 0.06f, "Περιγραφή" to 0.34f,
                    "Spots ΑΓ." to 0.12f, "Secs ΑΓ." to 0.12f, "Ημ/νία Έκδ." to 0.20f,
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
                            (if (line.isGift) "Διαφημίσεις  Δ Ω Ρ Α" else "Προϊόν ERP (εκκρεμεί)") to 0.34f,
                            "${line.placements}" to 0.12f,
                            "${line.totalSeconds}" to 0.12f,
                            (line.entryDate ?: "") to 0.20f,
                        )
                    }
                }

                // ═══ ΜΗΝΥΜΑΤΑ ══════════════════════════════════════════
                SectionTitle("Μηνύματα")
                HeaderRow(
                    "Περιγραφή Μηνύματος" to 0.52f, "Χρόνος (secs)" to 0.16f,
                    "Αναλωμένα Spots" to 0.16f, "Αναλωμένα Secs" to 0.16f,
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
                    TextButton(onClick = { onIntent(TimetableIntent.ClearFinder) }) { Text("Καθαρισμός") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = finder.selectedSpot != null,
                        onClick = { onIntent(TimetableIntent.CloseFinder) }
                    ) {
                        Text("Επιλογή", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { onIntent(TimetableIntent.CloseFinder) }) { Text("Άκυρο") }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
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
            Text(
                label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
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
            Text(
                value, fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(weight)
            )
        }
    }
}
