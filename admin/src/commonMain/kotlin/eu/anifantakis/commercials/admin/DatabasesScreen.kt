package eu.anifantakis.commercials.admin

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Hosted-databases administration (super admin): every registered station
 * with its schema, footprint and reachability - and deletion in two flavours:
 *
 * - SAFE delete: unhost only. Removes the stations.yaml entry, revokes every
 *   user's grant on it and unregisters it live - the MySQL schema stays
 *   untouched on its server (re-add the yaml entry to bring it back).
 * - HARD delete: safe delete + DROP DATABASE on the station's MySQL server.
 *   Irreversible; requires typing the station id.
 */
@Composable
fun DatabasesScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val adminApi = koinInject<AdminApi>()

    var stations by remember { mutableStateOf<List<HostedStation>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<HostedStation?>(null) }

    fun reload() {
        scope.launch {
            adminApi.listStations()
                .onSuccess { stations = it; error = null }
                .onFailure { error = it.message }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Hosted Databases", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Reload")
            }
        }

        message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(stations, key = { it.id }) { station ->
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
                        IconButton(onClick = { deleteTarget = station }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete database",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { station ->
        DeleteStationDialog(
            adminApi = adminApi,
            station = station,
            onDone = { result ->
                deleteTarget = null
                message = result
                reload()
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun DeleteStationDialog(
    adminApi: AdminApi,
    station: HostedStation,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var hard by remember { mutableStateOf(false) }
    var confirmId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Delete '${station.name}'") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !hard, onClick = { hard = false })
                    Column {
                        Text("Safe delete", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "Unhost only: removes the stations.yaml entry and every user's access. " +
                                "The MySQL schema (${station.database}) stays untouched.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = hard, onClick = { hard = true })
                    Column {
                        Text("Hard delete", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        Text(
                            "Safe delete + DROP DATABASE on the MySQL server. Irreversible.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedTextField(
                    value = confirmId, onValueChange = { confirmId = it },
                    label = { Text("Type the station id to confirm: ${station.id}") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && confirmId.trim() == station.id,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        adminApi.deleteStation(station.id, if (hard) "hard" else "safe", confirmId.trim())
                            .onSuccess { onDone(it.status + " (grants removed: ${it.grantsRemoved})") }
                            .onFailure { error = it.message; busy = false }
                    }
                }
            ) {
                Text(
                    if (hard) "HARD delete" else "Safe delete",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}
