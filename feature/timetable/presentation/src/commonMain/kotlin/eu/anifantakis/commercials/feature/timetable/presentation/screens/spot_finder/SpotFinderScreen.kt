package eu.anifantakis.commercials.feature.timetable.presentation.screens.spot_finder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppLoadingIndicator
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadioRow
import eu.anifantakis.commercials.core.presentation.design_system.components.AppSpinner
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.string_resources.LocalLanguage
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.grids.ColumnDef
import eu.anifantakis.commercials.grids.EnhancedDataGrid
import eu.anifantakis.commercials.grids.SelectionMode
import eu.anifantakis.commercials.grids.StickyRowsConfig
import eu.anifantakis.commercials.grids.rememberEnhancedDataGridState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration.Companion.milliseconds

/**
 * The legacy "Εύρεση" Details Console as its OWN screen, hosted in a floating
 * window (AppWindowHost) with its own keyed-scope [SpotFinderViewModel] -
 * minimize keeps the search alive, close destroys it, and the SELECTION
 * (armed spot, filter subjects) survives both in the flow's common state.
 *
 * Layout kept close to the original: three stacked table sections - ΠΕΛΑΤΗΣ
 * (search + matches), ΣΥΜΒΟΛΑΙΑ ΠΕΛΑΤΗ (contract lines), ΜΗΝΥΜΑΤΑ (spots) -
 * and Επιλογή/Άκυρο bottom-right. "Επιλογή" arms the grid's 'a' key.
 */
@Composable
fun SpotFinderScreenRoot(
    onClose: () -> Unit,
    viewModel: SpotFinderViewModel = koinViewModel(),
) {
    SpotFinderScreen(
        state = viewModel.state,
        onIntent = viewModel::onAction,
        onClose = onClose,
    )
}

@Composable
private fun SpotFinderScreen(
    state: SpotFinderState,
    onIntent: (SpotFinderIntent) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(UIConst.paddingCompact)) {

            // ═══ ΠΕΛΑΤΗΣ ═══════════════════════════════════════════
            SectionTitle(Strings[StringKey.FINDER_SECTION_CUSTOMER])
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppRadioRow(
                    selected = state.kind == PartyKind.CUSTOMER,
                    onClick = { onIntent(SpotFinderIntent.KindChanged(PartyKind.CUSTOMER)) },
                    label = Strings[StringKey.FINDER_TAB_CUSTOMERS],
                )
                Spacer(Modifier.width(UIConst.paddingCompact))
                AppRadioRow(
                    selected = state.kind == PartyKind.TRADER,
                    onClick = { onIntent(SpotFinderIntent.KindChanged(PartyKind.TRADER)) },
                    label = Strings[StringKey.FINDER_TAB_ADVERTISERS],
                )
                Spacer(Modifier.width(UIConst.paddingRegular))
                AppTextField(
                    value = state.query,
                    onValueChange = { onIntent(SpotFinderIntent.QueryChanged(it)) },
                    label = Strings[StringKey.FINDER_SEARCH_LABEL],
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        if (state.searching) {
                            AppSpinner()
                        }
                    }
                )
            }
            // While a search runs the matches fill the table; the
            // chosen party stays pinned as its single (highlighted) row.
            val partyRows = remember(state.results, state.selection.party) {
                state.results.ifEmpty { listOfNotNull(state.selection.party) }.toImmutableList()
            }
            FinderTable(
                items = partyRows,
                columns = partyColumns(),
                selectedKey = state.selection.party?.code?.takeIf { state.results.isEmpty() },
                rowKey = { it.code },
                onRowClick = { onIntent(SpotFinderIntent.PartySelected(it)) },
                modifier = Modifier.fillMaxWidth().weight(0.24f),
            )

            // ═══ ΣΥΜΒΟΛΑΙΑ ΠΕΛΑΤΗ ══════════════════════════════════
            SectionTitle(Strings[StringKey.FINDER_SECTION_CONTRACTS])
            FinderTable(
                items = state.lines,
                columns = contractColumns(),
                selectedKey = state.selection.line?.lineId,
                rowKey = { it.lineId },
                onRowClick = { onIntent(SpotFinderIntent.LineSelected(it)) },
                modifier = Modifier.fillMaxWidth().weight(0.3f),
            )

            // ═══ ΜΗΝΥΜΑΤΑ ══════════════════════════════════════════
            SectionTitle(Strings[StringKey.FINDER_SECTION_MESSAGES])
            FinderTable(
                items = state.selection.spots,
                columns = spotColumns(),
                selectedKey = state.selection.spot?.spotId,
                rowKey = { it.spotId },
                onRowClick = { onIntent(SpotFinderIntent.SpotSelected(it)) },
                modifier = Modifier.fillMaxWidth().weight(0.3f),
            )

            // ═══ Επιλογή / Άκυρο (bottom-right, like the original) ══
            Row(
                Modifier.fillMaxWidth().padding(top = UIConst.paddingSmall),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppButton(
                    text = Strings[StringKey.FINDER_CLEAR],
                    onClick = { onIntent(SpotFinderIntent.Clear) },
                    variant = AppButtonVariant.TEXT,
                )
                Spacer(Modifier.weight(1f))
                // Επιλογή is the console's primary action - the selection is
                // already armed in the shared state; the button just closes.
                AppButton(
                    text = Strings[StringKey.FINDER_SELECT],
                    onClick = onClose,
                    enabled = state.selection.spot != null,
                )
                AppButton(
                    text = Strings[StringKey.COMMON_CANCEL],
                    onClick = onClose,
                    variant = AppButtonVariant.TEXT,
                )
            }
        }

        // Drilling down (party -> contracts -> messages) used to push a spinner
        // INTO the column, so every click shoved the sections below it down and
        // back - the "jumps". An overlay changes no layout at all, and the
        // grace period means the usual sub-100ms load shows nothing whatsoever;
        // only a genuinely slow one dims the console.
        AppLoadingIndicator(
            isLoading = state.loadingLines || state.loadingSpots,
            appearAfter = FINDER_SPINNER_GRACE,
        )
    }
}

/**
 * How long a finder drill-down may take before it is worth telling the user
 * about. Comfortably above the measured local round trip, comfortably below
 * the ~1s where an unexplained wait starts to feel broken.
 */
private val FINDER_SPINNER_GRACE = 250.milliseconds

/**
 * The contracts table's date cell: the ERP issue date, or the contract's
 * PERIOD when that is absent. Load-bearing, not cosmetic: legacy doc numbers
 * repeat (a party can hold two contracts both numbered «18»), so without a
 * date the finder shows two identical rows and the operator picks blind.
 */
private fun ContractLine.dateLabel(): String =
    entryDate ?: listOfNotNull(startDate, endDate).joinToString(" → ")

@Composable
private fun SectionTitle(text: String) {
    AppText(
        text, AppTextStyle.TABLE_HEADER,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = UIConst.paddingSmall, bottom = UIConst.paddingHairline)
    )
}

/**
 * One section of the Εύρεση console, on the SAME [EnhancedDataGrid] the Break
 * Console uses - so the two consoles share resizable/reorderable columns, the
 * sticky header, sortable headers, scrollbars and keyboard navigation instead
 * of the hand-rolled weight tables this dialog used to draw.
 *
 * The finder's selection is the VIEWMODEL's ([selectedKey]), not the grid's:
 * a click dispatches, and the grid's own highlight is re-derived whenever the
 * list changes ([items] is the effect's key). Matching by KEY, not by index,
 * is what survives the party list collapsing to its single chosen row.
 */
@Composable
private fun <T> FinderTable(
    items: ImmutableList<T>,
    columns: ImmutableList<ColumnDef<T>>,
    selectedKey: Any?,
    rowKey: (T) -> Any,
    onRowClick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberEnhancedDataGridState(columns)
    // Keyed on the LIST (and on the selection appearing/vanishing) - never on
    // WHICH row is selected. A click is already highlighted by the grid itself,
    // at its OWN index; re-deriving that index from the source list would put
    // the highlight on the wrong row whenever a sortable header is active. What
    // still needs syncing is the list changing underneath: the party table
    // collapsing to its single chosen row, a new search, a cleared finder.
    LaunchedEffect(items, selectedKey == null) {
        val index = selectedKey?.let { key -> items.indexOfFirst { rowKey(it) == key } } ?: -1
        if (index >= 0) {
            state.selectedRows = setOf(index)
            state.focusedRow = index
        } else {
            state.clearSelection()
        }
    }
    val empty = Strings[StringKey.FINDER_NO_ROWS]
    EnhancedDataGrid(
        items = items,
        columns = columns,
        modifier = modifier,
        state = state,
        selectionMode = SelectionMode.SINGLE,
        // The console packs three tables into one window, so it runs denser
        // than the Break Console's full-screen grid.
        scale = AppTheme.fontSizeStep.factor,
        rowHeight = 26.dp,
        headerHeight = 30.dp,
        showRowNumbers = false,
        stickyRows = StickyRowsConfig(stickyHeader = true, stickyFooter = false),
        onRowClick = { item, _ -> onRowClick(item) },
        rowKey = rowKey,
        emptyContent = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppText(empty, AppTextStyle.NOTE, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/*
 * The three finder tables' columns. Remembered against the language (headers
 * resolve through the non-composable .localized()) and the font-size step -
 * the grid scales its own type and rows, but column WIDTHS live here, so a
 * bigger step has to rebuild them wider or the text merely ellipsizes.
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
                width = 120.dp * scale,
                extractor = { it.code },
            ),
            ColumnDef(
                id = "name",
                header = StringKey.FINDER_COL_NAME.localized(),
                width = 380.dp * scale,
                extractor = { it.name },
            ),
            ColumnDef(
                id = "vat",
                header = StringKey.FINDER_COL_VAT.localized(),
                width = 130.dp * scale,
                extractor = { it.vatNumber ?: "" },
            ),
            ColumnDef(
                id = "phone",
                header = StringKey.FINDER_COL_PHONE.localized(),
                width = 140.dp * scale,
                extractor = { it.phone ?: "" },
            ),
            ColumnDef(
                id = "spots",
                header = StringKey.FINDER_COL_SPOTS.localized(),
                width = 90.dp * scale,
                alignment = TextAlign.End,
                headerAlignment = TextAlign.End,
                extractor = { it.spotCount.toString() },
                comparator = compareBy { it.spotCount },
            ),
        )
    }
}

@Composable
private fun contractColumns(): ImmutableList<ColumnDef<ContractLine>> {
    val language = LocalLanguage.current
    val scale = AppTheme.fontSizeStep.factor
    val gift = Strings[StringKey.FINDER_GIFT_LINE]
    val pending = Strings[StringKey.FINDER_ERP_PENDING]
    return remember(language, scale, gift, pending) {
        persistentListOf(
            ColumnDef<ContractLine>(
                id = "contract",
                header = StringKey.FINDER_COL_CONTRACT.localized(),
                width = 110.dp * scale,
                extractor = { it.contractNumber },
                // Contract numbers are numeric strings: "703" must not sort
                // before "89" the way lexicographic ordering would put it.
                comparator = compareBy({ it.contractNumber.toLongOrNull() ?: Long.MAX_VALUE }, { it.lineNo }),
            ),
            ColumnDef(
                id = "line",
                header = StringKey.FINDER_COL_LINE.localized(),
                width = 70.dp * scale,
                alignment = TextAlign.End,
                headerAlignment = TextAlign.End,
                extractor = { it.lineNo.toString() },
                comparator = compareBy { it.lineNo },
            ),
            ColumnDef(
                id = "description",
                header = StringKey.FINDER_COL_DESCRIPTION.localized(),
                width = 300.dp * scale,
                extractor = { if (it.isGift) gift else pending },
            ),
            ColumnDef(
                id = "placements",
                header = StringKey.FINDER_COL_SPOTS_BOUGHT.localized(),
                width = 120.dp * scale,
                alignment = TextAlign.End,
                headerAlignment = TextAlign.End,
                extractor = { it.placements.toString() },
                comparator = compareBy { it.placements },
            ),
            ColumnDef(
                id = "seconds",
                header = StringKey.FINDER_COL_SECS_BOUGHT.localized(),
                width = 120.dp * scale,
                alignment = TextAlign.End,
                headerAlignment = TextAlign.End,
                extractor = { it.totalSeconds.toString() },
                comparator = compareBy { it.totalSeconds },
            ),
            ColumnDef(
                id = "date",
                header = StringKey.FINDER_COL_ISSUE_DATE.localized(),
                width = 210.dp * scale,
                extractor = { it.dateLabel() },
            ),
        )
    }
}

@Composable
private fun spotColumns(): ImmutableList<ColumnDef<ContractLineSpot>> {
    val language = LocalLanguage.current
    val scale = AppTheme.fontSizeStep.factor
    return remember(language, scale) {
        persistentListOf(
            ColumnDef<ContractLineSpot>(
                id = "description",
                header = StringKey.FINDER_COL_MSG_DESCRIPTION.localized(),
                width = 520.dp * scale,
                extractor = { it.description },
            ),
            ColumnDef(
                id = "duration",
                header = StringKey.FINDER_COL_DURATION.localized(),
                width = 130.dp * scale,
                alignment = TextAlign.End,
                headerAlignment = TextAlign.End,
                extractor = { it.durationSeconds.toString() },
                comparator = compareBy { it.durationSeconds },
            ),
            ColumnDef(
                id = "usedSpots",
                header = StringKey.FINDER_COL_USED_SPOTS.localized(),
                width = 140.dp * scale,
                alignment = TextAlign.End,
                headerAlignment = TextAlign.End,
                extractor = { it.placements.toString() },
                comparator = compareBy { it.placements },
            ),
            ColumnDef(
                id = "usedSecs",
                header = StringKey.FINDER_COL_USED_SECS.localized(),
                width = 140.dp * scale,
                alignment = TextAlign.End,
                headerAlignment = TextAlign.End,
                extractor = { it.totalSeconds.toString() },
                comparator = compareBy { it.totalSeconds },
            ),
        )
    }
}
