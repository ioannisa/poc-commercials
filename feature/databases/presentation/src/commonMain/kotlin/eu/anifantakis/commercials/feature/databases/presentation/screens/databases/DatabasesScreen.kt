package eu.anifantakis.commercials.feature.databases.presentation.screens.databases

import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onNavIntent(DatabasesScreenNavIntent.OnBack) }) {
                Icon(AppIcons.arrowBack, contentDescription = "Back")
            }
            Text("Hosted Databases", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onIntent(DatabasesIntent.Reload) }) {
                Icon(AppIcons.refresh, contentDescription = "Reload")
            }
        }

        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.stations, key = { it.id }) { station ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(station.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(station.id, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                station.database,
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (!station.reachable) "UNREACHABLE"
                                else "${station.placements ?: 0} placements · ${station.dateRange ?: "empty"}",
                                fontSize = 12.sp,
                                color = if (station.reachable) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(onClick = { onIntent(DatabasesIntent.DeleteRequested(station)) }) {
                            Icon(
                                AppIcons.delete,
                                contentDescription = "Delete database",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
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
    AlertDialog(
        onDismissRequest = { onIntent(DatabasesIntent.DismissDelete) },
        title = { Text("Delete '${station.name}'") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !dialog.hard, onClick = { onIntent(DatabasesIntent.DeleteModeChanged(false)) })
                    Column {
                        Text("Safe delete", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "Unhost only: removes the server.yaml entry and every user's access. " +
                                "The MySQL schema (${station.database}) stays untouched.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = dialog.hard, onClick = { onIntent(DatabasesIntent.DeleteModeChanged(true)) })
                    Column {
                        Text("Hard delete", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        Text(
                            "Safe delete + DROP DATABASE on the MySQL server. Irreversible.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedTextField(
                    value = dialog.confirmId,
                    onValueChange = { onIntent(DatabasesIntent.ConfirmIdChanged(it)) },
                    label = { Text("Type the station id to confirm: ${station.id}") },
                    singleLine = true, enabled = !dialog.busy, modifier = Modifier.fillMaxWidth()
                )
                dialog.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = dialog.canConfirm,
                onClick = { onIntent(DatabasesIntent.ConfirmDelete) }
            ) {
                Text(
                    if (dialog.hard) "HARD delete" else "Safe delete",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !dialog.busy, onClick = { onIntent(DatabasesIntent.DismissDelete) }) { Text("Cancel") }
        }
    )
}
