package eu.anifantakis.commercials.feature.migration_console.presentation.screens.migration

import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
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
        onNavIntent = { navIntent ->
            when (navIntent) {
                MigrationScreenNavIntent.OnBack -> onBack()
            }
        },
        onBrowseClicked = {
            if (nativeFilePickerAvailable) {
                scope.launch {
                    pickFileNative(StringKey.MIGRATION_SELECT_DUMP.localized(), "sql")?.let {
                        viewModel.onAction(MigrationIntent.DumpPathChanged(it))
                    }
                }
            } else {
                viewModel.onAction(MigrationIntent.OpenBrowser())
            }
        },
        // The SEN exports are a FOLDER on the server; the server-side browser
        // handles folder picking on every platform (no native dir picker).
        onBrowseSenClicked = { viewModel.onAction(MigrationIntent.OpenBrowser(forSenDir = true)) },
    )
}

/**
 * Navigation-only actions of this screen — ALWAYS routed through this single
 * parameter (a predictable shape you can expect on every screen, ready to
 * accept more nav without a refactor). Not a ViewModel [MigrationIntent];
 * onBrowseClicked triggers a platform file picker, not navigation.
 */
private sealed interface MigrationScreenNavIntent {
    data object OnBack : MigrationScreenNavIntent
}

@Composable
private fun MigrationScreen(
    state: MigrationState,
    onIntent: (MigrationIntent) -> Unit,
    onNavIntent: (MigrationScreenNavIntent) -> Unit,
    onBrowseClicked: () -> Unit,
    onBrowseSenClicked: () -> Unit,
) {
    val status = state.status

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onNavIntent(MigrationScreenNavIntent.OnBack) }) {
                Icon(AppIcons.arrowBack, contentDescription = Strings[StringKey.COMMON_BACK])
            }
            Text(Strings[StringKey.PREFERENCES_MIGRATION], fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                        Strings[StringKey.MIGRATION_COMPLETE_TITLE],
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        Strings[StringKey.MIGRATION_COMPLETE_BODY].withArgs(listOf(status.schema ?: "")),
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
                        Strings[StringKey.MIGRATION_FAILED_TITLE],
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        status.error ?: Strings[StringKey.MIGRATION_SEE_LOG],
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // ── step 1: source & target ─────────────────────────────────────
        if (status.state == "IDLE") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Strings[StringKey.MIGRATION_STEP1], fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.dumpPath,
                            onValueChange = { onIntent(MigrationIntent.DumpPathChanged(it)) },
                            label = { Text(Strings[StringKey.MIGRATION_DUMP_PATH]) },
                            placeholder = { Text("/backups/commercials3.sql") },
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        // Desktop gets the real OS file dialog; web/mobile
                        // fall back to browsing the server's filesystem.
                        Button(onClick = onBrowseClicked) { Text(Strings[StringKey.MIGRATION_BROWSE]) }
                    }
                    // Optional SEN (Oracle ERP) export folder: when given, the
                    // migration follows the transform with the ERP enrichment
                    // (real customer names/VAT/contacts, real contract periods).
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.senDirPath,
                            onValueChange = { onIntent(MigrationIntent.SenDirChanged(it)) },
                            label = { Text(Strings[StringKey.MIGRATION_SEN_DIR]) },
                            placeholder = { Text("/backups/SEN") },
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        Button(onClick = onBrowseSenClicked) { Text(Strings[StringKey.MIGRATION_BROWSE]) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.host,
                            onValueChange = { onIntent(MigrationIntent.HostChanged(it)) },
                            label = { Text(Strings[StringKey.MIGRATION_MYSQL_HOST]) }, singleLine = true, modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = state.port,
                            onValueChange = { onIntent(MigrationIntent.PortChanged(it)) },
                            label = { Text(Strings[StringKey.MIGRATION_PORT]) }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.username,
                            onValueChange = { onIntent(MigrationIntent.UsernameChanged(it)) },
                            label = { Text(Strings[StringKey.MIGRATION_MYSQL_USERNAME]) }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = { onIntent(MigrationIntent.PasswordChanged(it)) },
                            label = { Text(Strings[StringKey.MIGRATION_MYSQL_PASSWORD]) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = state.schema,
                        onValueChange = { onIntent(MigrationIntent.SchemaChanged(it)) },
                        label = { Text(Strings[StringKey.MIGRATION_TARGET_SCHEMA]) },
                        placeholder = { Text("commercials_mystation") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.createSchema,
                            onCheckedChange = { onIntent(MigrationIntent.CreateSchemaChanged(it)) }
                        )
                        Text(Strings[StringKey.MIGRATION_CREATE_SCHEMA], fontSize = 13.sp)
                    }
                    state.formError?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    Button(
                        enabled = state.canStart,
                        onClick = { onIntent(MigrationIntent.Start) }
                    ) { Text(Strings[StringKey.MIGRATION_START]) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── step 2: flow choice ─────────────────────────────────────────
        if (status.state == "AWAITING_FLOW") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        Strings[StringKey.MIGRATION_STEP2].withArgs(listOf(status.schema ?: "")),
                        fontWeight = FontWeight.Bold, fontSize = 14.sp
                    )
                    Text(
                        Strings[StringKey.MIGRATION_FLOW_INFO],
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    status.flows.forEach { flow ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = state.selectedFlow == flow.forTv,
                                onClick = { onIntent(MigrationIntent.FlowSelected(flow.forTv)) }
                            )
                            Text(
                                Strings[StringKey.MIGRATION_FLOW_ITEM].withArgs(listOf(
                                    Strings[if (flow.forTv == 1) StringKey.COMMON_TV else StringKey.COMMON_RADIO],
                                    flow.spots, flow.placements,
                                )),
                                fontSize = 13.sp
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.addToYaml,
                            onCheckedChange = { onIntent(MigrationIntent.AddToYamlChanged(it)) }
                        )
                        Text(Strings[StringKey.MIGRATION_ADD_TO_YAML], fontSize = 13.sp)
                    }
                    if (state.addToYaml) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.stationId,
                                onValueChange = { onIntent(MigrationIntent.StationIdChanged(it)) },
                                label = { Text(Strings[StringKey.MIGRATION_STATION_ID]) },
                                singleLine = true, modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = state.stationName,
                                onValueChange = { onIntent(MigrationIntent.StationNameChanged(it)) },
                                label = { Text(Strings[StringKey.USER_MGMT_DISPLAY_NAME]) },
                                singleLine = true, modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    state.formError?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    Button(
                        enabled = state.canChooseFlow,
                        onClick = { onIntent(MigrationIntent.ChooseFlow) }
                    ) { Text(Strings[StringKey.MIGRATION_MIGRATE_FLOW]) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── summary ─────────────────────────────────────────────────────
        status.summary?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Strings[StringKey.MIGRATION_SUMMARY], fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(Strings[StringKey.MIGRATION_SUM_BREAKS].withArgs(listOf(s.breaks)), fontSize = 13.sp)
                    Text(Strings[StringKey.MIGRATION_SUM_PROGRAMS].withArgs(listOf(s.programs)), fontSize = 13.sp)
                    Text(
                        Strings[StringKey.MIGRATION_SUM_CUSTOMERS].withArgs(listOf(s.customers, s.customers - s.customersSynthetic, s.customersSynthetic)),
                        fontSize = 13.sp
                    )
                    Text(Strings[StringKey.MIGRATION_SUM_CONTRACTS].withArgs(listOf(s.contracts, s.contractsSynthetic, s.contractLines)), fontSize = 13.sp)
                    Text(Strings[StringKey.MIGRATION_SUM_SPOTS].withArgs(listOf(s.spots, s.placements)), fontSize = 13.sp)
                    Text(Strings[StringKey.MIGRATION_SUM_COMMENTS].withArgs(listOf(s.flowComments, s.printAudits)), fontSize = 13.sp)
                    Text(Strings[StringKey.MIGRATION_SUM_RANGE].withArgs(listOf(s.dateRange)), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        Strings[StringKey.MIGRATION_COVERAGE].withArgs(listOf(s.placements, s.dumpScheduleRows)) +
                            (if (s.otherFlowRows > 0) Strings[StringKey.MIGRATION_COVERAGE_OTHER_FLOW].withArgs(listOf(s.otherFlowRows)) else "") +
                            (if (s.orphanedRows > 0) Strings[StringKey.MIGRATION_COVERAGE_ORPHANED].withArgs(listOf(s.orphanedRows)) else "") +
                            (if (s.zeroDateRows > 0) Strings[StringKey.MIGRATION_COVERAGE_ZERO_DATES].withArgs(listOf(s.zeroDateRows)) else ""),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        Strings[StringKey.MIGRATION_SYNTHETIC_NOTE],
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        status.error?.let {
            Text(Strings[StringKey.MIGRATION_ERROR].withArgs(listOf(it)), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }

        if (status.state in setOf("DONE", "FAILED")) {
            TextButton(onClick = { onIntent(MigrationIntent.Reset) }) { Text(Strings[StringKey.MIGRATION_START_ANOTHER]) }
            Spacer(Modifier.height(8.dp))
        }

        state.browser?.let { browser ->
            ServerFileBrowserDialog(browser = browser, onIntent = onIntent)
        }

        // ── live log ────────────────────────────────────────────────────
        if (status.log.isNotEmpty()) {
            Text(Strings[StringKey.MIGRATION_PROGRESS], fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
 * Navigates the SERVER's filesystem (directories + dump/export files) so the
 * operator can pick a dump - or, in [ServerBrowserState.forSenDir] mode, the
 * SEN export FOLDER - without typing paths. Works identically from web and
 * desktop clients because the listing comes over the admin API.
 */
@Composable
private fun ServerFileBrowserDialog(
    browser: ServerBrowserState,
    onIntent: (MigrationIntent) -> Unit,
) {
    val listing = browser.listing
    AlertDialog(
        onDismissRequest = { onIntent(MigrationIntent.CloseBrowser) },
        title = {
            Text(Strings[if (browser.forSenDir) StringKey.MIGRATION_PICK_SEN_DIR else StringKey.MIGRATION_PICK_DUMP])
        },
        text = {
            Column {
                Text(
                    listing?.path ?: "…",
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                browser.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it.asString(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
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
                                Icon(AppIcons.folder, null, modifier = Modifier.height(18.dp))
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
                                    when {
                                        entry.isDir -> onIntent(MigrationIntent.BrowseTo("$base/${entry.name}"))
                                        // folder mode: files are context, not choices
                                        browser.forSenDir -> Unit
                                        else -> onIntent(MigrationIntent.DumpPicked("$base/${entry.name}"))
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (entry.isDir) AppIcons.folder else AppIcons.description,
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
        confirmButton = {
            if (browser.forSenDir && listing != null) {
                TextButton(onClick = { onIntent(MigrationIntent.SenDirPicked(listing.path)) }) {
                    Text(Strings[StringKey.MIGRATION_USE_THIS_FOLDER])
                }
            }
        },
        dismissButton = { TextButton(onClick = { onIntent(MigrationIntent.CloseBrowser) }) { Text(Strings[StringKey.COMMON_CANCEL]) } }
    )
}
