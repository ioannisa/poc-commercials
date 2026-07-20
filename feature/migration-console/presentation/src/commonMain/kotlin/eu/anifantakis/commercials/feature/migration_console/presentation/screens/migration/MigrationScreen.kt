package eu.anifantakis.commercials.feature.migration_console.presentation.screens.migration

import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCard
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCheckboxRow
import eu.anifantakis.commercials.core.presentation.design_system.components.AppDialog
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconSize
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadioRow
import eu.anifantakis.commercials.core.presentation.design_system.components.AppSpinner
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.design_system.components.AppWireframeField
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.files.nativeFilePickerAvailable
import eu.anifantakis.commercials.core.presentation.files.pickFileNative
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.layout.fillMaxHeight
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationFlowInfo
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationGroup
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationProgress
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStationTally
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStatus
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationSummary
import eu.anifantakis.commercials.core.presentation.design_system.components.AppVerticalScrollbar
import eu.anifantakis.commercials.core.presentation.design_system.components.AppProgressBar
import eu.anifantakis.commercials.core.presentation.design_system.components.AppLogConsole
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import kotlinx.collections.immutable.persistentMapOf
import androidx.compose.ui.tooling.preview.Preview

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
        modifier = Modifier.fillMaxSize().padding(UIConst.paddingRegular).verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(
                label = Strings[StringKey.COMMON_BACK],
                icon = AppDrawableRepo.arrowBack,
                onClick = { onNavIntent(MigrationScreenNavIntent.OnBack) },
            )
            AppText(Strings[StringKey.PREFERENCES_MIGRATION], AppTextStyle.SCREEN_TITLE)
            Spacer(Modifier.weight(1f))
            if (state.running) AppSpinner()
            Spacer(Modifier.width(UIConst.paddingSmall))
            AppText(status.state, AppTextStyle.BODY_STRONG, color = MaterialTheme.colorScheme.primary)
        }

        // ── completion banner: unmissable outcome ───────────────────────
        if (status.state == "DONE") {
            AppCard(
                Modifier.fillMaxWidth().padding(vertical = UIConst.paddingSmall),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(Modifier.padding(UIConst.paddingRegular)) {
                    AppText(
                        Strings[StringKey.MIGRATION_COMPLETE_TITLE],
                        AppTextStyle.ITEM_TITLE,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    AppText(
                        Strings[StringKey.MIGRATION_COMPLETE_BODY].withArgs(listOf(status.schema ?: "")),
                        AppTextStyle.BODY,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        if (status.state == "FAILED") {
            AppCard(
                Modifier.fillMaxWidth().padding(vertical = UIConst.paddingSmall),
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ) {
                Column(Modifier.padding(UIConst.paddingRegular)) {
                    AppText(
                        Strings[StringKey.MIGRATION_FAILED_TITLE],
                        AppTextStyle.ITEM_TITLE,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    AppText(
                        status.error ?: Strings[StringKey.MIGRATION_SEE_LOG],
                        AppTextStyle.BODY,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // ── step 1: source & target ─────────────────────────────────────
        if (status.state == "IDLE") {
            AppCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(UIConst.paddingRegular),
                    verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)
                ) {
                    AppText(Strings[StringKey.MIGRATION_STEP1], AppTextStyle.SECTION_TITLE)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)
                    ) {
                        AppTextField(
                            value = state.dumpPath,
                            onValueChange = { onIntent(MigrationIntent.DumpPathChanged(it)) },
                            label = Strings[StringKey.MIGRATION_DUMP_PATH],
                            placeholder = "/backups/commercials3.sql",
                            modifier = Modifier.weight(1f),
                        )
                        // Desktop gets the real OS file dialog; web/mobile
                        // fall back to browsing the server's filesystem.
                        AppButton(text = Strings[StringKey.MIGRATION_BROWSE], onClick = onBrowseClicked)
                    }
                    // Optional SEN (Oracle ERP) export folder: when given, the
                    // migration follows the transform with the ERP enrichment
                    // (real customer names/VAT/contacts, real contract periods).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)
                    ) {
                        AppTextField(
                            value = state.senDirPath,
                            onValueChange = { onIntent(MigrationIntent.SenDirChanged(it)) },
                            label = Strings[StringKey.MIGRATION_SEN_DIR],
                            placeholder = "/backups/SEN",
                            modifier = Modifier.weight(1f),
                        )
                        AppButton(text = Strings[StringKey.MIGRATION_BROWSE], onClick = onBrowseSenClicked)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
                        AppTextField(
                            value = state.host,
                            onValueChange = { onIntent(MigrationIntent.HostChanged(it)) },
                            label = Strings[StringKey.MIGRATION_MYSQL_HOST],
                            modifier = Modifier.weight(2f),
                        )
                        AppTextField(
                            value = state.port,
                            onValueChange = { onIntent(MigrationIntent.PortChanged(it)) },
                            label = Strings[StringKey.MIGRATION_PORT],
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
                        AppTextField(
                            value = state.username,
                            onValueChange = { onIntent(MigrationIntent.UsernameChanged(it)) },
                            label = Strings[StringKey.MIGRATION_MYSQL_USERNAME],
                            modifier = Modifier.weight(1f),
                        )
                        AppWireframeField(
                            value = state.password,
                            onValueChange = { onIntent(MigrationIntent.PasswordChanged(it)) },
                            label = Strings[StringKey.MIGRATION_MYSQL_PASSWORD],
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // The target GROUP owns the database. An existing group
                    // supplies its own jdbcUrl and credentials, so only a NEW
                    // one asks for a schema.
                    AppText(Strings[StringKey.MIGRATION_GROUP_SECTION], AppTextStyle.ITEM_TITLE)
                    AppText(Strings[StringKey.MIGRATION_GROUP_INFO], AppTextStyle.NOTE)
                    AppRadioRow(
                        selected = state.isNewGroup,
                        onClick = { onIntent(MigrationIntent.ExistingGroupSelected("")) },
                        label = Strings[StringKey.MIGRATION_GROUP_NEW],
                    )
                    status.groups.forEach { group ->
                        AppRadioRow(
                            selected = state.existingGroupId == group.id,
                            onClick = { onIntent(MigrationIntent.ExistingGroupSelected(group.id)) },
                            label = "${group.name} (${group.schema})",
                        )
                    }
                    if (state.isNewGroup) {
                        Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
                            AppTextField(
                                value = state.groupId,
                                onValueChange = { onIntent(MigrationIntent.GroupIdChanged(it)) },
                                label = Strings[StringKey.MIGRATION_GROUP_ID],
                                modifier = Modifier.weight(1f),
                            )
                            AppTextField(
                                value = state.groupName,
                                onValueChange = { onIntent(MigrationIntent.GroupNameChanged(it)) },
                                label = Strings[StringKey.MIGRATION_GROUP_NAME],
                                modifier = Modifier.weight(1f),
                            )
                        }
                        AppTextField(
                            value = state.schema,
                            onValueChange = { onIntent(MigrationIntent.SchemaChanged(it)) },
                            label = Strings[StringKey.MIGRATION_TARGET_SCHEMA],
                            placeholder = "commercials_mygroup",
                        )
                        AppCheckboxRow(
                            checked = state.createSchema,
                            onCheckedChange = { onIntent(MigrationIntent.CreateSchemaChanged(it)) },
                            label = Strings[StringKey.MIGRATION_CREATE_SCHEMA],
                        )
                    }
                    state.formError?.let { AppText(it.asString(), AppTextStyle.ERROR_NOTE) }
                    AppButton(
                        text = Strings[StringKey.MIGRATION_START],
                        onClick = { onIntent(MigrationIntent.Start) },
                        enabled = state.canStart,
                    )
                }
            }
            Spacer(Modifier.height(UIConst.paddingCompact))
        }

        // ── step 2: map every flow to a station of the group ─────────────
        // Not a choice between the flows - they are the SAME company's TV and
        // radio, sharing its customers and contracts, so they migrate together.
        if (status.state == "AWAITING_FLOW") {
            AppCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(UIConst.paddingRegular),
                    verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)
                ) {
                    AppText(
                        Strings[StringKey.MIGRATION_STEP2].withArgs(listOf(status.schema ?: "")),
                        AppTextStyle.SECTION_TITLE,
                    )
                    AppText(
                        Strings[StringKey.MIGRATION_FLOW_INFO],
                        AppTextStyle.NOTE,
                    )
                    status.flows.forEach { flow ->
                        val target = state.flowTargets[flow.forTv] ?: FlowTarget()
                        Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall)) {
                            AppText(
                                Strings[StringKey.MIGRATION_FLOW_ITEM].withArgs(listOf(
                                    Strings[if (flow.forTv == 1) StringKey.COMMON_TV else StringKey.COMMON_RADIO],
                                    flow.spots, flow.placements,
                                )) + if (target.stationId.isBlank())
                                    " — ${Strings[StringKey.MIGRATION_FLOW_SKIPPED]}" else "",
                                AppTextStyle.BODY_STRONG,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
                                AppTextField(
                                    value = target.stationId,
                                    onValueChange = {
                                        onIntent(MigrationIntent.FlowStationIdChanged(flow.forTv, it))
                                    },
                                    label = Strings[StringKey.MIGRATION_FLOW_STATION_HINT],
                                    modifier = Modifier.weight(1f),
                                )
                                AppTextField(
                                    value = target.stationName,
                                    onValueChange = {
                                        onIntent(MigrationIntent.FlowStationNameChanged(flow.forTv, it))
                                    },
                                    label = Strings[StringKey.USER_MGMT_DISPLAY_NAME],
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    AppCheckboxRow(
                        checked = state.addToYaml,
                        onCheckedChange = { onIntent(MigrationIntent.AddToYamlChanged(it)) },
                        label = Strings[StringKey.MIGRATION_ADD_TO_YAML],
                    )
                    state.formError?.let { AppText(it.asString(), AppTextStyle.ERROR_NOTE) }
                    AppButton(
                        text = Strings[StringKey.MIGRATION_MIGRATE_FLOWS],
                        onClick = { onIntent(MigrationIntent.ChooseMapping) },
                        enabled = state.canMap,
                    )
                }
            }
            Spacer(Modifier.height(UIConst.paddingCompact))
        }

        // ── summary ─────────────────────────────────────────────────────
        status.summary?.let { s ->
            AppCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(UIConst.paddingRegular),
                    verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall)
                ) {
                    AppText(Strings[StringKey.MIGRATION_SUMMARY], AppTextStyle.SECTION_TITLE)
                    AppText(Strings[StringKey.MIGRATION_SUM_BREAKS].withArgs(listOf(s.breaks)), AppTextStyle.BODY)
                    AppText(Strings[StringKey.MIGRATION_SUM_PROGRAMS].withArgs(listOf(s.programs)), AppTextStyle.BODY)
                    AppText(
                        Strings[StringKey.MIGRATION_SUM_CUSTOMERS].withArgs(listOf(s.customers, s.customers - s.customersSynthetic, s.customersSynthetic)),
                        AppTextStyle.BODY,
                    )
                    AppText(Strings[StringKey.MIGRATION_SUM_CONTRACTS].withArgs(listOf(s.contracts, s.contractsSynthetic, s.contractLines)), AppTextStyle.BODY)
                    AppText(Strings[StringKey.MIGRATION_SUM_SPOTS].withArgs(listOf(s.spots, s.placements)), AppTextStyle.BODY)
                    // One dump filled several stations - show what each got, and
                    // say plainly that the customers/contracts above are shared.
                    s.stations.forEach { st ->
                        AppText(
                            Strings[StringKey.MIGRATION_SUM_STATION]
                                .withArgs(listOf(st.stationId, st.spots, st.placements)),
                            AppTextStyle.BODY,
                        )
                    }
                    if (s.stations.isNotEmpty()) {
                        AppText(Strings[StringKey.MIGRATION_SUM_SHARED], AppTextStyle.NOTE)
                    }
                    AppText(Strings[StringKey.MIGRATION_SUM_COMMENTS].withArgs(listOf(s.flowComments, s.printAudits)), AppTextStyle.BODY)
                    AppText(Strings[StringKey.MIGRATION_SUM_RANGE].withArgs(listOf(s.dateRange)), AppTextStyle.BODY_STRONG)
                    AppText(
                        Strings[StringKey.MIGRATION_COVERAGE].withArgs(listOf(s.placements, s.dumpScheduleRows)) +
                            (if (s.otherFlowRows > 0) Strings[StringKey.MIGRATION_COVERAGE_OTHER_FLOW].withArgs(listOf(s.otherFlowRows)) else "") +
                            (if (s.orphanedRows > 0) Strings[StringKey.MIGRATION_COVERAGE_ORPHANED].withArgs(listOf(s.orphanedRows)) else "") +
                            (if (s.zeroDateRows > 0) Strings[StringKey.MIGRATION_COVERAGE_ZERO_DATES].withArgs(listOf(s.zeroDateRows)) else ""),
                        AppTextStyle.BODY_STRONG,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    AppText(Strings[StringKey.MIGRATION_SYNTHETIC_NOTE], AppTextStyle.NOTE)
                }
            }
            Spacer(Modifier.height(UIConst.paddingCompact))
        }

        status.error?.let {
            AppText(Strings[StringKey.MIGRATION_ERROR].withArgs(listOf(it)), AppTextStyle.ERROR_NOTE)
            Spacer(Modifier.height(UIConst.paddingSmall))
        }

        if (status.state in setOf("DONE", "FAILED")) {
            AppButton(
                text = Strings[StringKey.MIGRATION_START_ANOTHER],
                onClick = { onIntent(MigrationIntent.Reset) },
                variant = AppButtonVariant.TEXT,
            )
            Spacer(Modifier.height(UIConst.paddingSmall))
        }

        state.browser?.let { browser ->
            ServerFileBrowserDialog(browser = browser, onIntent = onIntent)
        }

        // ── live progress + log ─────────────────────────────────────────
        if (status.log.isNotEmpty()) {
            AppText(Strings[StringKey.MIGRATION_PROGRESS], AppTextStyle.SECTION_TITLE)
            Spacer(Modifier.height(UIConst.paddingExtraSmall))

            // The bar only appears while the server has something MEASURED to
            // report (see MigrationProgress). A finished or failed run clears it
            // server-side rather than freezing it at 97%, which would read as
            // "still working".
            status.progress?.let { p ->
                AppProgressBar(
                    fraction = p.fraction,
                    caption = "${migrationPhaseLabel(p.phase)} — ${p.label}",
                    detail = migrationProgressDetail(p),
                )
                // WITHIN the running step, when it is big enough to measure
                // its inside: the placements bulk load alone parks the step
                // bar at 18/18 for minutes, and this is what still moves.
                p.subFraction?.let { sub ->
                    Spacer(Modifier.height(UIConst.paddingExtraSmall))
                    AppProgressBar(
                        fraction = sub,
                        caption = Strings[StringKey.MIGRATION_STEP_PROGRESS],
                        detail = "${p.subDone}/${p.subTotal}",
                    )
                }
                Spacer(Modifier.height(UIConst.paddingSmall))
            }

            AppLogConsole(lines = status.log)
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
    AppDialog(
        title = Strings[if (browser.forSenDir) StringKey.MIGRATION_PICK_SEN_DIR else StringKey.MIGRATION_PICK_DUMP],
        onDismiss = { onIntent(MigrationIntent.CloseBrowser) },
        // AppDialog always renders its confirm slot; in dump-pick mode the
        // rows themselves are the choice, so the confirm stays disabled.
        confirmText = Strings[StringKey.MIGRATION_USE_THIS_FOLDER],
        onConfirm = { listing?.let { onIntent(MigrationIntent.SenDirPicked(it.path)) } },
        confirmEnabled = browser.forSenDir && listing != null,
        dismissText = Strings[StringKey.COMMON_CANCEL],
    ) {
        AppText(
            listing?.path ?: "…",
            AppTextStyle.LOG_LINE,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        browser.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }
        // A directory can hold hundreds of entries; without a visible scrollbar
        // the pane looks like it is showing all of them.
        val browseState = rememberLazyListState()
        Row(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 380.dp)) {
        LazyColumn(state = browseState, modifier = Modifier.weight(1f).fillMaxHeight()) {
            listing?.parent?.let { parent ->
                item(key = "..") {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onIntent(MigrationIntent.BrowseTo(parent)) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(AppDrawableRepo.folder, size = AppIconSize.SMALL)
                        Spacer(Modifier.width(UIConst.paddingSmall))
                        AppText("..", AppTextStyle.BODY_STRONG)
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
                    AppIcon(
                        if (entry.isDir) AppDrawableRepo.folder else AppDrawableRepo.description,
                        size = AppIconSize.SMALL,
                        tint = if (entry.isDir) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(UIConst.paddingSmall))
                    AppText(entry.name, AppTextStyle.BODY, modifier = Modifier.weight(1f))
                    if (!entry.isDir) {
                        AppText("${entry.sizeBytes / 1_048_576} MB", AppTextStyle.TINY)
                    }
                }
            }
        }
        AppVerticalScrollbar(
            lazyListState = browseState,
            modifier = Modifier.fillMaxHeight(),
        )
        }
    }
}


/** The server names the phase; the operator reads it in his own language. */
@Composable
private fun migrationPhaseLabel(phase: String): String = Strings[
    when (phase) {
        "REPLAY" -> StringKey.MIGRATION_PHASE_REPLAY
        "TRANSFORM" -> StringKey.MIGRATION_PHASE_TRANSFORM
        else -> StringKey.MIGRATION_PHASE_ENRICH
    }
]

/**
 * The bar's right-hand figure, in the PHASE'S OWN UNIT - megabytes while reading
 * the dump, steps while transforming or enriching. Not a percentage: the percent
 * is already the bar, and "1204/1707 MB" is what tells the operator whether to go
 * and make coffee.
 */
private fun migrationProgressDetail(p: MigrationProgress): String? = when {
    p.total <= 0L -> null
    p.phase == "REPLAY" -> "${p.done}/${p.total} MB"
    else -> "${p.done}/${p.total}"
}

// ═══ Previews ══════════════════════════════════════════════════════════
//
// The wizard is a STATE MACHINE (IDLE -> REPLAYING -> AWAITING_FLOW ->
// TRANSFORMING -> DONE|FAILED) and each state draws a different screen. One
// happy-path preview would therefore only ever show the form - which is the
// one state that never breaks. So there is a preview per state.

/** The hosted groups the wizard offers as targets (same in every preview). */
private val previewGroups = listOf(
    MigrationGroup(id = "crete-media", name = "Crete Media Group", schema = "commercials_crete"),
    MigrationGroup(id = "aegean-media", name = "Aegean Media Group", schema = "commercials_aegean"),
)

private val previewLog = listOf(
    "[00:00:01] Opening dump /backups/commercials3.sql (1707 MB)",
    "[00:00:02] Replaying schema commercials_crete",
    "[00:01:14] customers: 1842 rows",
    "[00:02:38] contracts: 3910 rows",
    "[00:04:05] spots: 6332 rows",
    "[00:07:51] schedule: 168558 rows",
)

private val previewSummary = MigrationSummary(
    breaks = 8421,
    customers = 1842,
    customersSynthetic = 214,
    contracts = 3910,
    contractsSynthetic = 96,
    contractLines = 7745,
    spots = 6332,
    placements = 168558,
    flowComments = 402,
    printAudits = 1188,
    dateRange = "2011-01-01 .. 2026-06-30",
    dumpScheduleRows = 170_112,
    otherFlowRows = 1_204,
    orphanedRows = 318,
    zeroDateRows = 32,
    programs = 276,
    stations = listOf(
        MigrationStationTally(stationId = "crete-tv", forTv = 1, spots = 4820, placements = 128_340),
        MigrationStationTally(stationId = "crete-radio", forTv = 0, spots = 1512, placements = 40_218),
    ),
)

/** A filled-in form, nothing running yet: the operator is about to press Start. */
@Preview
@Composable
private fun MigrationScreenPreview() = AppPreview(padded = false) {
    MigrationScreen(
        state = MigrationState(
            status = MigrationStatus(state = "IDLE", groups = previewGroups),
            dumpPath = "/backups/commercials3.sql",
            senDirPath = "/backups/SEN",
            host = "localhost",
            port = "3306",
            username = "root",
            password = "s3cret",
            groupId = "crete-media",
            groupName = "Crete Media Group",
            schema = "commercials_crete",
        ),
        onIntent = {},
        onNavIntent = {},
        onBrowseClicked = {},
        onBrowseSenClicked = {},
    )
}

/** A replay in flight: the spinner, the measured bar and the live log. */
@Preview
@Composable
private fun MigrationScreenRunningPreview() = AppPreview(padded = false) {
    MigrationScreen(
        state = MigrationState(
            status = MigrationStatus(
                state = "REPLAYING",
                log = previewLog,
                progress = MigrationProgress(
                    phase = "REPLAY",
                    label = "commercials3.sql",
                    done = 1204,
                    total = 1707,
                ),
                schema = "commercials_crete",
                groups = previewGroups,
            ),
            dumpPath = "/backups/commercials3.sql",
            username = "root",
        ),
        onIntent = {},
        onNavIntent = {},
        onBrowseClicked = {},
        onBrowseSenClicked = {},
    )
}

/** Step 2: the dump's flows are counted and each is mapped to a station. */
@Preview
@Composable
private fun MigrationScreenAwaitingMappingPreview() = AppPreview(padded = false) {
    MigrationScreen(
        state = MigrationState(
            status = MigrationStatus(
                state = "AWAITING_FLOW",
                log = previewLog,
                schema = "commercials_crete",
                flows = listOf(
                    MigrationFlowInfo(forTv = 1, spots = 4820, placements = 128_340),
                    MigrationFlowInfo(forTv = 0, spots = 1512, placements = 40_218),
                ),
                groups = previewGroups,
            ),
            dumpPath = "/backups/commercials3.sql",
            username = "root",
            flowTargets = persistentMapOf(
                1 to FlowTarget(stationId = "crete-tv", stationName = "Crete TV"),
                0 to FlowTarget(stationId = "crete-radio", stationName = "Crete Radio"),
            ),
        ),
        onIntent = {},
        onNavIntent = {},
        onBrowseClicked = {},
        onBrowseSenClicked = {},
    )
}

/** The outcome banner + the summary the operator actually reads. */
@Preview
@Composable
private fun MigrationScreenDonePreview() = AppPreview(padded = false) {
    MigrationScreen(
        state = MigrationState(
            status = MigrationStatus(
                state = "DONE",
                log = previewLog + "[00:12:44] DONE - commercials_crete",
                schema = "commercials_crete",
                summary = previewSummary,
                groups = previewGroups,
            ),
        ),
        onIntent = {},
        onNavIntent = {},
        onBrowseClicked = {},
        onBrowseSenClicked = {},
    )
}

/** The failure the happy path never shows: banner, error line, Start another. */
@Preview
@Composable
private fun MigrationScreenFailedPreview() = AppPreview(padded = false) {
    MigrationScreen(
        state = MigrationState(
            status = MigrationStatus(
                state = "FAILED",
                log = previewLog + "[00:03:02] FAILED",
                schema = "commercials_crete",
                error = "Access denied for user 'root'@'localhost' (using password: YES)",
                groups = previewGroups,
            ),
        ),
        onIntent = {},
        onNavIntent = {},
        onBrowseClicked = {},
        onBrowseSenClicked = {},
    )
}
