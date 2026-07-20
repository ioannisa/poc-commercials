package eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable

import kotlinx.datetime.DayOfWeek
import eu.anifantakis.commercials.grids.SchedulerLabels
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.core.domain.auth.StationAccess
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
import eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable.components.KeyboardEnabledHeader

/**
 * The scheduler grid screen. The Εύρεση finder is its OWN screen now
 * (screens/spot_finder), opened as a floating window via [onOpenSpotFinder];
 * this screen keeps only the Μηνύματα header that displays and re-arms the
 * shared selection. The cells live in the flow's common state, the narrow
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
    onOpenSpotFinder: () -> Unit,
    onOpenProgramTypes: () -> Unit,
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
                TimetableScreenNavIntent.OnOpenSpotFinder -> onOpenSpotFinder()
                TimetableScreenNavIntent.OnOpenProgramTypes -> onOpenProgramTypes()
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
// `internal`, not `private`: the header lives in a sibling file now
// (TimetableHeader.kt) and raises these. Still the screen's own vocabulary -
// the Root below is the only place that maps them onto callbacks.
internal sealed interface TimetableScreenNavIntent {
    data object OnOpenEmailDialog : TimetableScreenNavIntent
    data object OnLogout : TimetableScreenNavIntent
    data object OnPreferences : TimetableScreenNavIntent
    data object OnAiChat : TimetableScreenNavIntent
    /** The Εύρεση console - a floating window, so opening it is NAVIGATION. */
    data object OnOpenSpotFinder : TimetableScreenNavIntent
    /** «Τύποι Προγράμματος» - likewise its own window now. */
    data object OnOpenProgramTypes : TimetableScreenNavIntent
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
                                    label = state.finder.spot
                                        ?.let { StringKey.TIMETABLE_ADD_SPOT_NAMED.localized().withArgs(listOf(it.description.take(30))) }
                                        ?: StringKey.TIMETABLE_ADD_SPOT_HINT.localized(),
                                    icon = { AppIcon(AppDrawableRepo.add, size = AppIconSize.SMALL) },
                                    shortcut = "A",
                                    enabled = state.finder.spot != null
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

