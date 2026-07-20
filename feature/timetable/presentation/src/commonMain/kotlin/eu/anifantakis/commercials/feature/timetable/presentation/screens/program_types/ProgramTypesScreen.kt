package eu.anifantakis.commercials.feature.timetable.presentation.screens.program_types

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppPopup
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppVerticalScrollbar
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.feature.timetable.domain.model.Program
import eu.anifantakis.commercials.grids.contrastTextColor
import org.koin.compose.viewmodel.koinViewModel

/**
 * «Τύποι Προγράμματος» - the programme catalog, as its own screen in a
 * floating window.
 *
 * It was the legacy console's brush dropdown plus four buttons squeezed into
 * the timetable header, with its four dialogs living in the grid screen. The
 * catalog is a workflow of its own (create / rename / recolour / soft
 * delete), so it gets its own screen and its own ViewModel; the header keeps
 * only the brush READOUT, which reads the shared selection.
 */
@Composable
fun ProgramTypesScreenRoot(
    onClose: () -> Unit,
    viewModel: ProgramTypesViewModel = koinViewModel(),
) {
    ProgramTypesScreen(
        state = viewModel.state,
        onIntent = viewModel::onAction,
        onClose = onClose,
    )
}

@Composable
private fun ProgramTypesScreen(
    state: ProgramTypesState,
    onIntent: (ProgramTypesIntent) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(UIConst.paddingRegular)) {
        // Type to narrow: the catalog runs to a couple of hundred entries, so
        // scrolling to one by eye is not a workflow.
        AppTextField(
            value = state.filter,
            onValueChange = { onIntent(ProgramTypesIntent.FilterChanged(it)) },
            label = Strings[StringKey.TIMETABLE_PROGRAM_FILTER],
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(UIConst.paddingSmall))

        // The catalog, one row per programme. A full list rather than the
        // header's dropdown: this window exists to MANAGE programmes, so the
        // whole catalog is the subject, not a control to pick one from.
        val listState = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(state.visible, key = { it.id }) { program ->
                val isSelected = program.id == state.armedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .clickable { onIntent(ProgramTypesIntent.Select(program.id)) }
                        .padding(horizontal = UIConst.paddingSmall, vertical = UIConst.paddingExtraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
                ) {
                    ProgramSwatch(program)
                    AppText(
                        program.name,
                        if (isSelected) AppTextStyle.BODY_STRONG else AppTextStyle.BODY,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        AppVerticalScrollbar(
            lazyListState = listState,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
        }

        // The legacy button colours: ΔΙΟΡΘ green, ΠΡΟΣΘ blue, ΑΦΑΙΡ red.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = UIConst.paddingSmall),
            horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniButton(Strings[StringKey.TIMETABLE_PROGRAM_EDIT], Color(0xFF2E7D32)) {
                onIntent(ProgramTypesIntent.OpenDialog(ProgramDialog.EDIT))
            }
            MiniButton(Strings[StringKey.TIMETABLE_PROGRAM_ADD], Color(0xFF1565C0)) {
                onIntent(ProgramTypesIntent.OpenDialog(ProgramDialog.ADD))
            }
            MiniButton(Strings[StringKey.TIMETABLE_PROGRAM_REMOVE], MaterialTheme.colorScheme.error) {
                onIntent(ProgramTypesIntent.OpenDialog(ProgramDialog.REMOVE))
            }
            // Χρώμα previews the brush: its face IS the selected colour.
            ColorButton(programColor(state.selected?.colorArgb)) {
                onIntent(ProgramTypesIntent.OpenDialog(ProgramDialog.COLOR))
            }
            Spacer(Modifier.weight(1f))
            AppButton(
                text = Strings[StringKey.COMMON_CLOSE],
                onClick = onClose,
                variant = AppButtonVariant.TEXT,
            )
        }
    }

    ProgramDialogHost(state = state, onIntent = onIntent)
}

// ═══ the ΔΙΟΡΘ / ΠΡΟΣΘ / ΑΦΑΙΡ / Χρώμα dialogs ══════════════════════════

/** Renders whichever catalog dialog [ProgramTypesState.dialog] holds. */
@Composable
private fun ProgramDialogHost(
    state: ProgramTypesState,
    onIntent: (ProgramTypesIntent) -> Unit,
) {
    val dialog = state.dialog ?: return
    val selected = state.selected
    val close = { onIntent(ProgramTypesIntent.CloseDialog) }
    when (dialog) {
        ProgramDialog.ADD -> ProgramNameDialog(
            title = Strings[StringKey.TIMETABLE_PROGRAM_ADD_TITLE],
            initialName = "",
            withPalette = true,
            onConfirm = { name, color -> onIntent(ProgramTypesIntent.Create(name, color)) },
            onDismiss = close,
        )
        ProgramDialog.EDIT -> selected?.let {
            ProgramNameDialog(
                title = Strings[StringKey.TIMETABLE_PROGRAM_EDIT_TITLE],
                initialName = it.name,
                withPalette = false,
                onConfirm = { name, _ -> onIntent(ProgramTypesIntent.Rename(name)) },
                onDismiss = close,
            )
        }
        ProgramDialog.COLOR -> selected?.let {
            ProgramColorDialog(
                program = it,
                onPick = { argb -> onIntent(ProgramTypesIntent.Recolor(argb)) },
                onDismiss = close,
            )
        }
        ProgramDialog.REMOVE -> selected?.let {
            ProgramRemoveDialog(
                program = it,
                onConfirm = { onIntent(ProgramTypesIntent.Remove) },
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

// ═══ shared bits (moved verbatim from the old header console) ═══════════

internal fun programColor(argb: Int?): Color? = argb?.let { Color(it.toLong() and 0xFFFFFFFF) }

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

/** Legacy-density outline button. */
@Composable
private fun MiniButton(text: String, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, color, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = UIConst.paddingSmall, vertical = UIConst.paddingExtraSmall),
        contentAlignment = Alignment.Center,
    ) {
        AppText(text, AppTextStyle.NOTE, color = color, maxLines = 1)
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
            .padding(horizontal = UIConst.paddingSmall, vertical = UIConst.paddingExtraSmall),
        contentAlignment = Alignment.Center,
    ) {
        AppText(Strings[StringKey.TIMETABLE_PROGRAM_COLOR], AppTextStyle.NOTE, color = fg, maxLines = 1)
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
