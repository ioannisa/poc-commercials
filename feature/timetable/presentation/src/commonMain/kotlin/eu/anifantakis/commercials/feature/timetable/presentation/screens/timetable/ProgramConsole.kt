package eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.grids.SchedulerKey
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppGroupBox
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppPopup
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.feature.timetable.domain.model.Program
import eu.anifantakis.commercials.grids.contrastTextColor

/*
 * The programme console - the legacy header's «Πρόσθεση νέου διαλείματος» and
 * «Τύποι Προγράμματος» boxes, live again on the break-entity model:
 *
 *  - the DROPDOWN is the operator's BRUSH: its programme (and colour) is what
 *    the next created break is born with. Adding into an already-painted cell
 *    ignores it - the spot takes the cell's own programme (the rule lives in
 *    TimetableViewModel / the server; this file only renders).
 *  - ΔΙΟΡΘ / ΠΡΟΣΘ / ΑΦΑΙΡ / Χρώμα manage the catalog. A programme's colour is
 *    DATA (never theme-adapted), so anything painted with it takes its text
 *    contrast from luminance - the grid's own rule.
 *  - «Πρόσθεση νέου διαλείματος» creates an EMPTY break at the typed ΩΩ:ΛΛ on
 *    the grid's focused day: the slot exists, painted, before anything sells
 *    into it.
 */

/** Packed-ARGB programme colour -> Compose colour (same cast as the grid). */
private fun programColor(argb: Int?): Color? = argb?.let { Color(it.toLong() and 0xFFFFFFFF) }

/**
 * The FOCUSED cell's break, shown the legacy-console way: the day/date/time on
 * top ("ΣΑ 18/07 - 13:00") and, under it, the break PAINTED with its
 * programme's colour (the name on it, text contrast by luminance) - so the
 * operator sees at a glance which programme sits behind the cell they are on.
 * A white/empty cell shows a blank swatch.
 */
@Composable
internal fun SelectedBreakReadout(
    state: TimetableState,
    modifier: Modifier = Modifier,
) {
    val slot = state.breaks.getOrNull(state.selectedRow) ?: return
    // selectedColumn is 0-based; a stale index past the month's end (navigating
    // to a shorter month) simply yields no date and the readout hides.
    val date = runCatching {
        LocalDate(state.year, state.month, state.selectedColumn + 1)
    }.getOrNull() ?: return

    val cell = state.cells[SchedulerKey(slot.time, date)]
    // The cell's zoneColor IS the break's programme colour (white when the
    // break is unpainted or the day is empty) - exactly what the grid draws.
    val brush = cell?.zoneColor?.takeIf { it != Color.White }
    val programName = cell?.programName

    // "ΣΑ 18/07 - 13:00": weekday abbr, zero-padded day/month, then the time.
    val dd = date.dayOfMonth.toString().padStart(2, '0')
    val mm = state.month.toString().padStart(2, '0')
    val label = "${dayAbbrev(date.dayOfWeek)} $dd/$mm - ${slot.label}"

    val shape = RoundedCornerShape(3.dp)
    Column(
        // FIXED width so the logo + station picker above never shift as the
        // programme name changes length; the swatch always sits at its
        // 2-line footprint.
        modifier = modifier.width(READOUT_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(UIConst.paddingHairline),
    ) {
        AppText(label, AppTextStyle.NOTE, maxLines = 1)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Reserve TWO lines always (min, not fixed - so a larger font
                // scale grows the box down, never clips the second line). A
                // third line ellipsizes.
                .heightIn(min = READOUT_TWO_LINE_HEIGHT)
                .clip(shape)
                .background(brush ?: MaterialTheme.colorScheme.surface, shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(horizontal = UIConst.paddingExtraSmall, vertical = UIConst.paddingHairline),
            contentAlignment = Alignment.Center,
        ) {
            AppText(
                programName ?: "—",
                AppTextStyle.NOTE,
                color = brush?.let(::contrastTextColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The readout's fixed footprint: wide enough for the longest name in 2 lines. */
private val READOUT_WIDTH = 236.dp
private val READOUT_TWO_LINE_HEIGHT = 36.dp

/** The 2-letter weekday abbreviation - the SAME strings the grid columns use. */
@Composable
private fun dayAbbrev(day: DayOfWeek): String = Strings[
    when (day) {
        DayOfWeek.MONDAY -> StringKey.DAY_SHORT_MONDAY
        DayOfWeek.TUESDAY -> StringKey.DAY_SHORT_TUESDAY
        DayOfWeek.WEDNESDAY -> StringKey.DAY_SHORT_WEDNESDAY
        DayOfWeek.THURSDAY -> StringKey.DAY_SHORT_THURSDAY
        DayOfWeek.FRIDAY -> StringKey.DAY_SHORT_FRIDAY
        DayOfWeek.SATURDAY -> StringKey.DAY_SHORT_SATURDAY
        else -> StringKey.DAY_SHORT_SUNDAY
    }
]

/**
 * The classic Windows 48-colour picker rows - the palette the legacy console's
 * «Χρώμα» button offered, and the COLORREF world every migrated programme
 * colour came from.
 */
private val programPalette: List<Int> = listOf(
    0xFF8080, 0xFFFF80, 0x80FF80, 0x00FF80, 0x80FFFF, 0x0080FF, 0xFF80C0, 0xFF80FF,
    0xFF0000, 0xFFFF00, 0x80FF00, 0x00FF40, 0x00FFFF, 0x0080C0, 0x8080C0, 0xFF00FF,
    0x804040, 0xFF8040, 0x00FF00, 0x008080, 0x004080, 0x8080FF, 0x800040, 0xFF0080,
    0x800000, 0xFF8000, 0x008000, 0x008040, 0x0000FF, 0x0000A0, 0x800080, 0x8000FF,
    0x400000, 0x804000, 0x004000, 0x004040, 0x000080, 0x000040, 0x400040, 0x400080,
    0x000000, 0x808000, 0x808040, 0x808080, 0x408080, 0xC0C0C0, 0xE0E0E0, 0xFFFFFF,
).map { (0xFF shl 24) or it }

/**
 * «Πρόσθεση νέου διαλείματος»: ΩΩ:ΛΛ + Πρόσθεση, exactly the legacy box. The
 * break lands on the grid's FOCUSED day, painted with the selected programme -
 * both guards (a programme is selected, the slot is free) live in the ViewModel.
 */
@Composable
internal fun AddBreakBox(
    state: TimetableState,
    onIntent: (TimetableIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppGroupBox(
        title = Strings[StringKey.TIMETABLE_ADD_BREAK_TITLE],
        verticalArrangement = Arrangement.Center,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppText(Strings[StringKey.TIMETABLE_ADD_BREAK_TIME_LABEL], AppTextStyle.NOTE)
            Spacer(Modifier.width(UIConst.paddingExtraSmall))
            LegacyTimeField(
                value = state.newBreakTime,
                onValueChange = { onIntent(TimetableIntent.NewBreakTimeChanged(it)) },
                onDone = { onIntent(TimetableIntent.AddBreak) },
            )
            Spacer(Modifier.width(UIConst.paddingSmall))
            AppButton(
                text = Strings[StringKey.TIMETABLE_ADD_BREAK_BUTTON],
                onClick = { onIntent(TimetableIntent.AddBreak) },
                variant = AppButtonVariant.SECONDARY,
            )
        }
    }
}

/**
 * A bare, legacy-density ΩΩ:ΛΛ field. The App wireframe field carries a
 * floating label and a reserved error line - correct for forms, far too tall
 * for this toolbar band, so the box draws its own thin frame (same idiom as
 * the band's other compacted controls).
 *
 * Input is TIME-ONLY: anything but digits and ':' is dropped at the keystroke
 * (letters never appear), the ':' lands BY ITSELF after the hour (see
 * [autoColon] - the user only ever types digits), and the placeholder is
 * "--:--" so an empty field cannot be mistaken for a filled one.
 */
@Composable
private fun LegacyTimeField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val shape = RoundedCornerShape(3.dp)
    BasicTextField(
        value = value,
        onValueChange = { raw -> onValueChange(autoColon(previous = value, raw = raw)) },
        singleLine = true,
        textStyle = AppTheme.typography.material.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    .padding(horizontal = UIConst.paddingExtraSmall, vertical = UIConst.paddingHairline),
                contentAlignment = Alignment.Center,
            ) {
                if (value.isEmpty()) {
                    AppText(
                        "--:--",
                        AppTextStyle.NOTE,
                        color = UIConst.grayOutColor(MaterialTheme.colorScheme.onSurface),
                    )
                }
                inner()
            }
        },
    )
}

/**
 * The ':' lands by itself - the operator only types DIGITS:
 *
 *  - "2","3" -> "23:" (second hour digit -> the colon appears); a second digit
 *    that would make an impossible hour flips to minutes instead: "9","5" ->
 *    "9:5" (95 is no hour), so one-digit hours need no colon either.
 *  - resumed typing after deletes: "23" + "5" -> "23:5".
 *  - PASTED colon-less digits read like the parser does - the last two are the
 *    minutes: "955" -> "9:55", "2355" -> "23:55".
 *
 * Deletion never re-inserts (the eager rules fire only when the text GREW), so
 * backspace walks cleanly back through "23:5" -> "23:" -> "23" -> "2".
 */
private fun autoColon(previous: String, raw: String): String {
    val cleaned = raw.filter { it.isDigit() || it == ':' }
    val bounded = if (':' in cleaned) cleaned.take(5) else cleaned.take(4)
    val grew = bounded.length > previous.length
    return when {
        ':' in bounded -> bounded
        grew && bounded.length == 2 ->
            if (bounded.toInt() <= 23) "$bounded:" else "${bounded.take(1)}:${bounded.drop(1)}"
        grew && bounded.length == 3 && bounded.length == previous.length + 1 ->
            "${bounded.take(2)}:${bounded.drop(2)}"
        bounded.length >= 3 -> "${bounded.dropLast(2)}:${bounded.takeLast(2)}"
        else -> bounded
    }
}

/**
 * «Τύποι Προγράμματος»: the brush dropdown - painted WITH the selected
 * programme's colour, like the legacy console - over the four catalog buttons.
 */
@Composable
internal fun ProgramTypesBox(
    state: TimetableState,
    onIntent: (TimetableIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppGroupBox(
        title = Strings[StringKey.TIMETABLE_PROGRAM_TYPES_TITLE],
        verticalArrangement = Arrangement.Center,
        modifier = modifier,
    ) {
        val selected = state.programs.firstOrNull { it.id == state.selectedProgramId }
        val brush = programColor(selected?.colorArgb)
        val fg = brush?.let(::contrastTextColor) ?: MaterialTheme.colorScheme.onSurfaceVariant
        val shape = RoundedCornerShape(3.dp)
        var menu by remember { mutableStateOf(false) }

        Box(modifier = Modifier.widthIn(min = 200.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(brush ?: MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    .clickable(enabled = state.programs.isNotEmpty()) { menu = true }
                    .padding(horizontal = UIConst.paddingExtraSmall, vertical = UIConst.paddingHairline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    selected?.name ?: Strings[StringKey.TIMETABLE_PROGRAM_SELECT_PLACEHOLDER],
                    AppTextStyle.NOTE,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AppIcon(AppDrawableRepo.arrowDropDown, tint = fg)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                state.programs.forEach { program ->
                    DropdownMenuItem(
                        leadingIcon = { ProgramSwatch(program) },
                        text = {
                            AppText(program.name, AppTextStyle.NOTE, color = LocalContentColor.current)
                        },
                        onClick = {
                            onIntent(TimetableIntent.SelectProgram(program.id))
                            menu = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(UIConst.paddingHairline))

        Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingHairline)) {
            // The legacy button colours: ΔΙΟΡΘ green, ΠΡΟΣΘ blue, ΑΦΑΙΡ red.
            MiniButton(Strings[StringKey.TIMETABLE_PROGRAM_EDIT], Color(0xFF2E7D32)) {
                onIntent(TimetableIntent.OpenProgramDialog(ProgramDialog.EDIT))
            }
            MiniButton(Strings[StringKey.TIMETABLE_PROGRAM_ADD], Color(0xFF1565C0)) {
                onIntent(TimetableIntent.OpenProgramDialog(ProgramDialog.ADD))
            }
            MiniButton(Strings[StringKey.TIMETABLE_PROGRAM_REMOVE], MaterialTheme.colorScheme.error) {
                onIntent(TimetableIntent.OpenProgramDialog(ProgramDialog.REMOVE))
            }
            // Χρώμα previews the brush: its face IS the selected colour.
            ColorButton(brush) {
                onIntent(TimetableIntent.OpenProgramDialog(ProgramDialog.COLOR))
            }
        }
    }
}

/** A programme's colour chip (transparent frame when it paints nothing). */
@Composable
private fun ProgramSwatch(program: Program) {
    Box(
        Modifier
            .size(14.dp)
            .background(programColor(program.colorArgb) ?: Color.Transparent, RoundedCornerShape(2.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
    )
}

/** Legacy-density outline button (the band compacts everything - see the header). */
@Composable
private fun MiniButton(text: String, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, color, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = UIConst.paddingExtraSmall, vertical = UIConst.paddingHairline),
        contentAlignment = Alignment.Center,
    ) {
        AppText(text, AppTextStyle.TINY, color = color, maxLines = 1)
    }
}

@Composable
private fun ColorButton(brush: Color?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    val fg = brush?.let(::contrastTextColor) ?: MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(shape)
            .background(brush ?: Color.Unspecified, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = UIConst.paddingExtraSmall, vertical = UIConst.paddingHairline),
        contentAlignment = Alignment.Center,
    ) {
        AppText(Strings[StringKey.TIMETABLE_PROGRAM_COLOR], AppTextStyle.TINY, color = fg, maxLines = 1)
    }
}

// ═══ the ΔΙΟΡΘ / ΠΡΟΣΘ / ΑΦΑΙΡ / Χρώμα dialogs ══════════════════════════

/** Renders whichever programme dialog [TimetableState.programDialog] holds. */
@Composable
internal fun ProgramDialogHost(state: TimetableState, onIntent: (TimetableIntent) -> Unit) {
    val dialog = state.programDialog ?: return
    val selected = state.programs.firstOrNull { it.id == state.selectedProgramId }
    val close = { onIntent(TimetableIntent.CloseProgramDialog) }
    when (dialog) {
        ProgramDialog.ADD -> ProgramNameDialog(
            title = Strings[StringKey.TIMETABLE_PROGRAM_ADD_TITLE],
            initialName = "",
            withPalette = true,
            onConfirm = { name, color -> onIntent(TimetableIntent.CreateProgram(name, color)) },
            onDismiss = close,
        )
        ProgramDialog.EDIT -> selected?.let {
            ProgramNameDialog(
                title = Strings[StringKey.TIMETABLE_PROGRAM_EDIT_TITLE],
                initialName = it.name,
                withPalette = false,
                onConfirm = { name, _ -> onIntent(TimetableIntent.RenameProgram(name)) },
                onDismiss = close,
            )
        }
        ProgramDialog.COLOR -> selected?.let {
            ProgramColorDialog(
                program = it,
                onPick = { argb -> onIntent(TimetableIntent.RecolorProgram(argb)) },
                onDismiss = close,
            )
        }
        ProgramDialog.REMOVE -> selected?.let {
            ProgramRemoveDialog(
                program = it,
                onConfirm = { onIntent(TimetableIntent.RemoveProgram) },
                onDismiss = close,
            )
        }
    }
}

/** ΠΡΟΣΘ (name + palette) and ΔΙΟΡΘ (name only) share this form. */
@Composable
private fun ProgramNameDialog(
    title: String,
    initialName: String,
    withPalette: Boolean,
    onConfirm: (name: String, colorArgb: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf<Int?>(null) }
    AppPopup(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).padding(UIConst.paddingRegular),
            verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
            AppText(title, AppTextStyle.SECTION_TITLE)
            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = Strings[StringKey.TIMETABLE_PROGRAM_NAME_LABEL],
            )
            if (withPalette) PaletteGrid(selected = color, onPick = { color = it })
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall, Alignment.End),
            ) {
                AppButton(
                    text = Strings[StringKey.COMMON_CANCEL],
                    onClick = onDismiss,
                    variant = AppButtonVariant.TEXT,
                )
                AppButton(
                    text = Strings[StringKey.COMMON_SAVE],
                    onClick = { onConfirm(name, color) },
                    enabled = name.isNotBlank(),
                )
            }
        }
    }
}

/** Χρώμα: pick a swatch - applying immediately, like the legacy colour box. */
@Composable
private fun ProgramColorDialog(
    program: Program,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AppPopup(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(UIConst.paddingRegular),
            verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
            AppText(
                "${Strings[StringKey.TIMETABLE_PROGRAM_COLOR_TITLE]}: ${program.name}",
                AppTextStyle.SECTION_TITLE,
            )
            PaletteGrid(selected = program.colorArgb, onPick = onPick)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall, Alignment.End),
            ) {
                AppButton(
                    text = Strings[StringKey.COMMON_CANCEL],
                    onClick = onDismiss,
                    variant = AppButtonVariant.TEXT,
                )
            }
        }
    }
}

/** ΑΦΑΙΡ: soft delete - the confirm text says painted cells keep their colour. */
@Composable
private fun ProgramRemoveDialog(
    program: Program,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppPopup(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).padding(UIConst.paddingRegular),
            verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
            AppText(
                "${Strings[StringKey.TIMETABLE_PROGRAM_REMOVE_TITLE]}: ${program.name}",
                AppTextStyle.SECTION_TITLE,
            )
            AppText(Strings[StringKey.TIMETABLE_PROGRAM_REMOVE_CONFIRM], AppTextStyle.BODY)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall, Alignment.End),
            ) {
                AppButton(
                    text = Strings[StringKey.COMMON_CANCEL],
                    onClick = onDismiss,
                    variant = AppButtonVariant.TEXT,
                )
                AppButton(
                    text = Strings[StringKey.COMMON_DELETE],
                    onClick = onConfirm,
                    variant = AppButtonVariant.DESTRUCTIVE,
                )
            }
        }
    }
}

/** The Windows-classic swatch grid; [selected] gets the accent frame. */
@Composable
private fun PaletteGrid(selected: Int?, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingHairline)) {
        programPalette.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingHairline)) {
                row.forEach { argb ->
                    val shape = RoundedCornerShape(3.dp)
                    val isSelected = argb == selected
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(shape)
                            .background(programColor(argb)!!, shape)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                shape = shape,
                            )
                            .clickable { onPick(argb) }
                    )
                }
            }
        }
    }
}
