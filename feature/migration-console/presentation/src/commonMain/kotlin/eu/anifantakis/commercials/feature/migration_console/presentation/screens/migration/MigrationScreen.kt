package eu.anifantakis.commercials.feature.migration_console.presentation.screens.migration

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.anifantakis.commercials.core.presentation.files.nativeFilePickerAvailable
import eu.anifantakis.commercials.core.presentation.files.pickFileNative
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Super-admin migration tool: point the SERVER at a legacy mysqldump file,
 * watch the replay live, pick which flow (TV/radio) to migrate when the
 * counts appear, and get the summary. The dump path is a path on the server
 * machine - the browser only steers.
 */
@Composable
fun MigrationScreenRoot(
    onBack: () -> Unit,
    viewModel: MigrationViewModel = koinViewModel(),
) {
    // The native OS file dialog is a platform capability, not state - it
    // stays in the Root; web/mobile fall back to the server-side browser.
    val scope = rememberCoroutineScope()

    MigrationScreen(
        state = viewModel.state,
        onIntent = viewModel::onAction,
        onBack = onBack,
        onBrowseClicked = {
            if (nativeFilePickerAvailable) {
                scope.launch {
                    pickFileNative("Select a legacy MySQL dump", "sql")?.let {
                        viewModel.onAction(MigrationIntent.DumpPathChanged(it))
                    }
                }
            } else {
                viewModel.onAction(MigrationIntent.OpenBrowser)
            }
        },
    )
}

@Composable
private fun MigrationScreen(
    state: MigrationState,
    onIntent: (MigrationIntent) -> Unit,
    onBack: () -> Unit,
    onBrowseClicked: () -> Unit,
) {
    val status = state.status

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Legacy Migration", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state.running) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(status.state, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }

        // ── completion banner: unmissable outcome ───────────────────────
        if (status.state == "DONE") {
            Card(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "✓ MIGRATION COMPLETE",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "All data from the dump has been migrated into '${status.schema}'. " +
                            "The station is hosted LIVE - grant users access now; it appears " +
                            "in dropdowns at their next login.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        if (status.state == "FAILED") {
            Card(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "✗ MIGRATION FAILED",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        status.error ?: "See the log below.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // ── step 1: source & target ─────────────────────────────────────
        if (status.state == "IDLE") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Source dump & target database", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.dumpPath,
                            onValueChange = { onIntent(MigrationIntent.DumpPathChanged(it)) },
                            label = { Text("Dump file path (on the server machine)") },
                            placeholder = { Text("/backups/commercials3.sql") },
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        // Desktop gets the real OS file dialog; web/mobile
                        // fall back to browsing the server's filesystem.
                        Button(onClick = onBrowseClicked) { Text("Browse…") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.host,
                            onValueChange = { onIntent(MigrationIntent.HostChanged(it)) },
                            label = { Text("MySQL host") }, singleLine = true, modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = state.port,
                            onValueChange = { onIntent(MigrationIntent.PortChanged(it)) },
                            label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.username,
                            onValueChange = { onIntent(MigrationIntent.UsernameChanged(it)) },
                            label = { Text("MySQL username") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = { onIntent(MigrationIntent.PasswordChanged(it)) },
                            label = { Text("MySQL password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = state.schema,
                        onValueChange = { onIntent(MigrationIntent.SchemaChanged(it)) },
                        label = { Text("Target schema name") },
                        placeholder = { Text("commercials_mystation") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.createSchema,
                            onCheckedChange = { onIntent(MigrationIntent.CreateSchemaChanged(it)) }
                        )
                        Text("Create the schema if it does not exist", fontSize = 13.sp)
                    }
                    state.formError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    Button(
                        enabled = state.canStart,
                        onClick = { onIntent(MigrationIntent.Start) }
                    ) { Text("Start migration") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── step 2: flow choice ─────────────────────────────────────────
        if (status.state == "AWAITING_FLOW") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "2. Choose the flow to migrate into '${status.schema}'",
                        fontWeight = FontWeight.Bold, fontSize = 14.sp
                    )
                    Text(
                        "A legacy database can serve both a TV and a radio flow - each becomes its own station.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    status.flows.forEach { flow ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = state.selectedFlow == flow.forTv,
                                onClick = { onIntent(MigrationIntent.FlowSelected(flow.forTv)) }
                            )
                            Text(
                                "${if (flow.forTv == 1) "TV" else "Radio"} — ${flow.spots} spots, ${flow.placements} placements",
                                fontSize = 13.sp
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.addToYaml,
                            onCheckedChange = { onIntent(MigrationIntent.AddToYamlChanged(it)) }
                        )
                        Text("Add the station to server.yaml", fontSize = 13.sp)
                    }
                    if (state.addToYaml) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.stationId,
                                onValueChange = { onIntent(MigrationIntent.StationIdChanged(it)) },
                                label = { Text("Station id (e.g. my-station)") },
                                singleLine = true, modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = state.stationName,
                                onValueChange = { onIntent(MigrationIntent.StationNameChanged(it)) },
                                label = { Text("Display name") },
                                singleLine = true, modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    state.formError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    Button(
                        enabled = state.canChooseFlow,
                        onClick = { onIntent(MigrationIntent.ChooseFlow) }
                    ) { Text("Migrate this flow") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── summary ─────────────────────────────────────────────────────
        status.summary?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Migration summary", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Break slots: ${s.breaks} (from real airing times)", fontSize = 13.sp)
                    Text("Programmes: ${s.programs} (with their operator-assigned colours)", fontSize = 13.sp)
                    Text(
                        "Customers: ${s.customers} (${s.customers - s.customersSynthetic} real names recovered, ${s.customersSynthetic} synthetic)",
                        fontSize = 13.sp
                    )
                    Text("Contracts: ${s.contracts} (${s.contractsSynthetic} synthetic) · lines: ${s.contractLines}", fontSize = 13.sp)
                    Text("Spots: ${s.spots} · Placements: ${s.placements}", fontSize = 13.sp)
                    Text("Flow comments: ${s.flowComments} · Print audits: ${s.printAudits}", fontSize = 13.sp)
                    Text("Date range: ${s.dateRange}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Coverage: migrated ${s.placements} of ${s.dumpScheduleRows} placements found in the dump" +
                            (if (s.otherFlowRows > 0) " · ${s.otherFlowRows} belong to the other flow (migrate them into a second station)" else "") +
                            (if (s.orphanedRows > 0) " · ${s.orphanedRows} orphaned (reference spots the legacy app purged)" else "") +
                            (if (s.zeroDateRows > 0) " · ${s.zeroDateRows} with invalid dates" else ""),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Synthetic rows are flagged in the database so a future ERP import can replace them.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        status.error?.let {
            Text("Error: $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }

        if (status.state in setOf("DONE", "FAILED")) {
            TextButton(onClick = { onIntent(MigrationIntent.Reset) }) { Text("Start another migration") }
            Spacer(Modifier.height(8.dp))
        }

        state.browser?.let { browser ->
            ServerFileBrowserDialog(browser = browser, onIntent = onIntent)
        }

        // ── live log ────────────────────────────────────────────────────
        if (status.log.isNotEmpty()) {
            Text("Progress", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            val listState = rememberLazyListState()
            LaunchedEffect(status.log.size) {
                if (status.log.isNotEmpty()) listState.scrollToItem(status.log.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 320.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp)
            ) {
                items(status.log) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * Navigates the SERVER's filesystem (directories + .sql files only) so the
 * operator can pick a dump without typing paths - works identically from
 * web and desktop clients because the listing comes over the admin API.
 */
@Composable
private fun ServerFileBrowserDialog(
    browser: ServerBrowserState,
    onIntent: (MigrationIntent) -> Unit,
) {
    val listing = browser.listing
    AlertDialog(
        onDismissRequest = { onIntent(MigrationIntent.CloseBrowser) },
        title = { Text("Pick a dump file (server filesystem)") },
        text = {
            Column {
                Text(
                    listing?.path ?: "…",
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                browser.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 380.dp)) {
                    listing?.parent?.let { parent ->
                        item(key = "..") {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onIntent(MigrationIntent.BrowseTo(parent)) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, null, modifier = Modifier.height(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("..", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    items(listing?.entries.orEmpty(), key = { it.name }) { entry ->
                        val base = listing?.path?.trimEnd('/') ?: ""
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    if (entry.isDir) onIntent(MigrationIntent.BrowseTo("$base/${entry.name}"))
                                    else onIntent(MigrationIntent.DumpPicked("$base/${entry.name}"))
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (entry.isDir) Icons.Default.Folder else Icons.Default.Description,
                                null,
                                modifier = Modifier.height(18.dp),
                                tint = if (entry.isDir) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(entry.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (!entry.isDir) {
                                Text(
                                    "${entry.sizeBytes / 1_048_576} MB",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onIntent(MigrationIntent.CloseBrowser) }) { Text("Cancel") } }
    )
}
