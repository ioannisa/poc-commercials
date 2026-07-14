package eu.anifantakis.commercials.feature.databases.presentation.screens.databases

import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCard
import eu.anifantakis.commercials.core.presentation.design_system.components.AppDialog
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadio
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import eu.anifantakis.commercials.core.presentation.helper.UiText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.anifantakis.commercials.feature.databases.domain.DeleteMode
import eu.anifantakis.commercials.feature.databases.domain.HostedStation
import kotlinx.collections.immutable.persistentListOf
import androidx.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

/**
 * Hosted-databases administration (super admin): every registered station
 * with its schema, footprint and reachability - and deletion in two flavours:
 *
 * - SAFE delete: unhost only. Removes the server.yaml entry, revokes every
 *   user's grant on it and unregisters it live - the MySQL schema stays
 *   untouched on its server (re-add the yaml entry to bring it back).
 * - HARD delete: safe delete + DROP DATABASE on the station's MySQL server.
 *   Irreversible; requires typing the station id.
 */
@Composable
fun DatabasesScreenRoot(
    onBack: () -> Unit,
    viewModel: DatabasesViewModel = koinViewModel(),
) {
    DatabasesScreen(
        state = viewModel.state,
        onIntent = viewModel::onAction,
        onNavIntent = { navIntent ->
            when (navIntent) {
                DatabasesScreenNavIntent.OnBack -> onBack()
            }
        },
    )
}

/**
 * Navigation-only actions of this screen — ALWAYS routed through this single
 * parameter (a predictable shape you can expect on every screen, ready to
 * accept more nav without a refactor). Not a ViewModel [DatabasesIntent].
 */
private sealed interface DatabasesScreenNavIntent {
    data object OnBack : DatabasesScreenNavIntent
}

@Composable
private fun DatabasesScreen(
    state: DatabasesState,
    onIntent: (DatabasesIntent) -> Unit,
    onNavIntent: (DatabasesScreenNavIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(UIConst.paddingRegular)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(
                label = Strings[StringKey.COMMON_BACK],
                icon = AppIcons.arrowBack,
                onClick = { onNavIntent(DatabasesScreenNavIntent.OnBack) },
            )
            AppText(Strings[StringKey.PREFERENCES_DATABASES], AppTextStyle.SCREEN_TITLE)
            Spacer(Modifier.weight(1f))
            AppIconButton(
                label = Strings[StringKey.DATABASES_CD_RELOAD],
                icon = AppIcons.refresh,
                onClick = { onIntent(DatabasesIntent.Reload) },
            )
        }

        state.message?.let {
            AppText(it.asString(), AppTextStyle.BODY, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(UIConst.paddingExtraSmall))
        }
        state.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
            Spacer(Modifier.height(UIConst.paddingExtraSmall))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
            items(state.stations, key = { it.id }) { station ->
                AppCard(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(UIConst.paddingCompact),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppText(station.name, AppTextStyle.ITEM_TITLE)
                                Spacer(Modifier.width(UIConst.paddingSmall))
                                AppText(station.id, AppTextStyle.NOTE)
                            }
                            AppText(
                                station.database,
                                AppTextStyle.LOG_LINE,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AppText(
                                if (!station.reachable) Strings[StringKey.DATABASES_UNREACHABLE]
                                else Strings[StringKey.DATABASES_STATION_SUMMARY].withArgs(
                                    listOf(station.placements ?: 0, station.dateRange ?: Strings[StringKey.DATABASES_EMPTY_RANGE])
                                ),
                                AppTextStyle.NOTE,
                                color = if (station.reachable) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.error
                            )
                        }
                        AppIconButton(
                            label = Strings[StringKey.DATABASES_CD_DELETE],
                            icon = AppIcons.delete,
                            onClick = { onIntent(DatabasesIntent.DeleteRequested(station)) },
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    state.delete?.let { dialog ->
        DeleteStationDialog(dialog = dialog, onIntent = onIntent)
    }
}

@Composable
private fun DeleteStationDialog(
    dialog: DeleteDialogState,
    onIntent: (DatabasesIntent) -> Unit,
) {
    val station = dialog.station
    AppDialog(
        title = Strings[StringKey.DATABASES_DELETE_TITLE].withArgs(listOf(station.name)),
        onDismiss = { onIntent(DatabasesIntent.DismissDelete) },
        confirmText = Strings[
            when (dialog.mode) {
                DeleteMode.SAFE -> StringKey.DATABASES_SAFE_DELETE
                DeleteMode.PURGE -> StringKey.DATABASES_PURGE_DELETE
                DeleteMode.DROP_GROUP -> StringKey.DATABASES_DROP_GROUP
            }
        ],
        onConfirm = { onIntent(DatabasesIntent.ConfirmDelete) },
        dismissText = Strings[StringKey.COMMON_CANCEL],
        confirmEnabled = dialog.canConfirm,
        confirmBusy = dialog.busy,
        destructive = true,
    ) {
        // Two-line labels (title + description): AppRadioRow only carries a
        // single string label, so these rows stay hand-built around AppRadio.
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppRadio(
                selected = dialog.mode == DeleteMode.SAFE,
                onClick = { onIntent(DatabasesIntent.DeleteModeChanged(DeleteMode.SAFE)) },
            )
            Column {
                AppText(Strings[StringKey.DATABASES_SAFE_DELETE], AppTextStyle.BODY_STRONG)
                AppText(
                    Strings[StringKey.DATABASES_SAFE_DELETE_DESC].withArgs(listOf(station.database)),
                    AppTextStyle.NOTE,
                )
            }
        }
        // The station's rows only. Its siblings keep the database, and so do the
        // group's shared customers and contracts.
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppRadio(
                selected = dialog.mode == DeleteMode.PURGE,
                onClick = { onIntent(DatabasesIntent.DeleteModeChanged(DeleteMode.PURGE)) },
            )
            Column {
                AppText(
                    Strings[StringKey.DATABASES_PURGE_DELETE],
                    AppTextStyle.BODY_STRONG,
                    color = MaterialTheme.colorScheme.error,
                )
                AppText(
                    Strings[StringKey.DATABASES_PURGE_DELETE_DESC].withArgs(listOf(station.groupName)),
                    AppTextStyle.NOTE,
                )
            }
        }
        // The whole group database - it takes every sibling station with it, so
        // the dialog names them and asks for the GROUP's id.
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppRadio(
                selected = dialog.mode == DeleteMode.DROP_GROUP,
                onClick = { onIntent(DatabasesIntent.DeleteModeChanged(DeleteMode.DROP_GROUP)) },
            )
            Column {
                AppText(
                    Strings[StringKey.DATABASES_DROP_GROUP],
                    AppTextStyle.BODY_STRONG,
                    color = MaterialTheme.colorScheme.error,
                )
                AppText(
                    Strings[StringKey.DATABASES_DROP_GROUP_DESC].withArgs(
                        listOf(station.groupName, station.database)
                    ),
                    AppTextStyle.NOTE,
                )
                if (station.siblings.isNotEmpty()) {
                    AppText(
                        Strings[StringKey.DATABASES_DROP_GROUP_SIBLINGS]
                            .withArgs(listOf(station.siblings.joinToString(", "))),
                        AppTextStyle.NOTE,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        AppTextField(
            value = dialog.confirmId,
            onValueChange = { onIntent(DatabasesIntent.ConfirmIdChanged(it)) },
            label = Strings[StringKey.DATABASES_CONFIRM_ID].withArgs(listOf(dialog.expectedConfirmation)),
            enabled = !dialog.busy,
        )
        dialog.error?.let { AppText(it.asString(), AppTextStyle.ERROR_NOTE) }
    }
}

// ── previews ────────────────────────────────────────────────────────────────

/**
 * Two stations SHARING a group database plus one unreachable station - the three
 * rows whose rendering differs. A list of three identical healthy stations would
 * prove nothing.
 */
private val previewStations = persistentListOf(
    HostedStation(
        id = "crete-tv",
        name = "Crete TV",
        database = "db1.internal:3306/commercials_crete",
        placements = 18_432,
        dateRange = "01/01/2024 - 31/07/2026",
        groupId = "crete-group",
        groupName = "Crete Media Group",
        siblings = listOf("Radio 984"),
    ),
    HostedStation(
        id = "radio-984",
        name = "Radio 984",
        database = "db1.internal:3306/commercials_crete",
        placements = 4_107,
        dateRange = "12/03/2025 - 30/06/2026",
        groupId = "crete-group",
        groupName = "Crete Media Group",
        siblings = listOf("Crete TV"),
    ),
    HostedStation(
        id = "aegean-fm",
        name = "Aegean FM",
        database = "db2.internal:3306/commercials_aegean",
        reachable = false,
        groupId = "aegean-group",
        groupName = "Aegean Broadcasting",
    ),
)

@Preview
@Composable
private fun DatabasesScreenPreview() = AppPreview(padded = false) {
    DatabasesScreen(
        state = DatabasesState(stations = previewStations),
        onIntent = {},
        onNavIntent = {},
    )
}

/** No hosted station yet - the header row is the whole screen. */
@Preview
@Composable
private fun DatabasesScreenEmptyPreview() = AppPreview(padded = false) {
    DatabasesScreen(
        state = DatabasesState(),
        onIntent = {},
        onNavIntent = {},
    )
}

/** The admin API is down: the banner must not be mistaken for a station row. */
@Preview
@Composable
private fun DatabasesScreenErrorPreview() = AppPreview(padded = false) {
    DatabasesScreen(
        state = DatabasesState(
            error = UiText.Dynamic("Could not reach the station admin API"),
        ),
        onIntent = {},
        onNavIntent = {},
    )
}
