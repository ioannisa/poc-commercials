package eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable

import kotlinx.datetime.DayOfWeek
import eu.anifantakis.commercials.grids.SchedulerLabels
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.core.presentation.util.toStringKey
import androidx.compose.ui.layout.ContentScale
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.components.AppAsyncImage
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppGroupBox
import eu.anifantakis.commercials.core.presentation.design_system.components.AppPendingBox
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconSize
import eu.anifantakis.commercials.core.presentation.design_system.components.AppPopup
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadioColumn
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadioRow
import eu.anifantakis.commercials.core.presentation.design_system.components.AppSelectionOption
import eu.anifantakis.commercials.core.presentation.design_system.components.AppSpinner
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
    onAiChat: () -> Unit,
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
                TimetableScreenNavIntent.OnAiChat -> onAiChat()
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
    data object OnAiChat : TimetableScreenNavIntent
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

        // The Εύρεση finder now lives INSIDE the header's "Μηνύματα" group box
        // (the legacy Messages box). Its dialog still opens over the whole screen.
        if (state.showFinder) {
            SpotFinderDialog(
                finder = finder,
                onIntent = onIntent,
            )
        }

        // The programme console's ΔΙΟΡΘ/ΠΡΟΣΘ/ΑΦΑΙΡ/Χρώμα dialogs (opened from
        // the header's «Τύποι Προγράμματος» box).
        ProgramDialogHost(state = state, onIntent = onIntent)

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
                        icon = { AppIcon(AppDrawableRepo.openInNew, size = AppIconSize.SMALL) },
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
                        icon = { AppIcon(AppDrawableRepo.print, size = AppIconSize.SMALL) },
                        enabled = cellData.any { it.key.date == date && it.value.spotCount > 0 }
                    ) {
                        onIntent(TimetableIntent.PrintDay(date))
                    })

                    // Print this break's commercials
                    add(ContextMenuEntry.Item(
                        label = StringKey.TIMETABLE_MENU_PRINT_BREAK.localized(),
                        icon = { AppIcon(AppDrawableRepo.print, size = AppIconSize.SMALL) },
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
                                if (showSpotTimes) AppDrawableRepo.numbers else AppDrawableRepo.timer,
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
                            icon = { AppIcon(AppDrawableRepo.edit, size = AppIconSize.SMALL) },
                            items = listOf(
                                ContextMenuEntry.Item(
                                    label = finder.selectedSpot
                                        ?.let { StringKey.TIMETABLE_ADD_SPOT_NAMED.localized().withArgs(listOf(it.description.take(30))) }
                                        ?: StringKey.TIMETABLE_ADD_SPOT_HINT.localized(),
                                    icon = { AppIcon(AppDrawableRepo.add, size = AppIconSize.SMALL) },
                                    shortcut = "A",
                                    enabled = finder.selectedSpot != null
                                ) {
                                    onIntent(TimetableIntent.AddSpotAt(breakSlot.time, date))
                                },
                                ContextMenuEntry.Item(
                                    label = StringKey.TIMETABLE_MENU_REMOVE_LAST.localized(),
                                    icon = { AppIcon(AppDrawableRepo.delete, size = AppIconSize.SMALL) },
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
                        icon = { AppIcon(AppDrawableRepo.print, size = AppIconSize.SMALL) },
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
                        icon = { AppIcon(AppDrawableRepo.print, size = AppIconSize.SMALL) }
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
                                ProgramTypesBox(
                                    state = state,
                                    onIntent = onIntent,
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
                        // "Προβολή Βάσει…" - the view filter, not migrated yet.
                        AppPendingBox(
                            title = Strings[StringKey.TIMETABLE_VIEW_BASED_ON_TITLE],
                            modifier = Modifier.fillMaxHeight(),
                        )

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
    finder: FinderUiState,
    onIntent: (TimetableIntent) -> Unit,
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
            "${Strings[StringKey.FINDER_COL_NAME]}: ${finder.selectedParty?.name ?: "—"}",
            AppTextStyle.NOTE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AppText(
            "${Strings[StringKey.TIMETABLE_CONTRACT_LABEL]}: ${
                finder.selectedLine?.let { "${it.contractNumber} / ${it.lineNo}" } ?: "—"
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
                finder.selectedLine?.let {
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
                onClick = { onIntent(TimetableIntent.OpenFinder) },
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
                        finder.selectedSpot?.description ?: Strings[StringKey.TIMETABLE_NO_SPOT_SELECTED],
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
            if (finder.selectedParty != null) {
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

