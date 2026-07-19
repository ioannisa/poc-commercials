package eu.anifantakis.commercials.feature.galaxy_bridge.presentation.screens.galaxy_bridge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCard
import eu.anifantakis.commercials.core.presentation.design_system.components.AppDialog
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppLogConsole
import eu.anifantakis.commercials.core.presentation.design_system.components.AppProgressBar
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadioRow
import eu.anifantakis.commercials.core.presentation.design_system.components.AppSpinner
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import eu.anifantakis.commercials.core.presentation.files.bytePickerAvailable
import eu.anifantakis.commercials.core.presentation.files.pickFileBytes
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyDelivery
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyGroup
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyProgress
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyReview
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyStatus
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxySummary
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Super-admin Galaxy Bridge: upload a periodic Galaxy (new ERP) delivery,
 * DRY-RUN it against a hosted group, read the reconciliation report and the
 * review list, then APPLY. The import runs ON THE SERVER; this screen only
 * uploads, steers and polls (works against a remote server).
 */
@Composable
fun GalaxyBridgeScreenRoot(
    onBack: () -> Unit,
    viewModel: GalaxyBridgeViewModel = koinViewModel(),
) {
    // Byte picking is a platform capability, not state - it stays in the Root.
    val scope = rememberCoroutineScope()

    GalaxyBridgeScreen(
        state = viewModel.state,
        onIntent = viewModel::onAction,
        onNavIntent = { navIntent ->
            when (navIntent) {
                GalaxyBridgeScreenNavIntent.OnBack -> onBack()
            }
        },
        uploadAvailable = bytePickerAvailable,
        onPickDelivery = {
            scope.launch {
                pickFileBytes(StringKey.GALAXY_SELECT_ZIP.localized(), "zip")?.let { picked ->
                    viewModel.onAction(
                        GalaxyBridgeIntent.UploadDelivery(
                            name = picked.name.removeSuffix(".zip"),
                            fileName = picked.name,
                            bytes = picked.bytes,
                        )
                    )
                }
            }
        },
        onPickDictionary = {
            scope.launch {
                pickFileBytes(StringKey.GALAXY_SELECT_ZIP.localized(), "zip")?.let { picked ->
                    viewModel.onAction(
                        GalaxyBridgeIntent.UploadDictionary(fileName = picked.name, bytes = picked.bytes)
                    )
                }
            }
        },
    )
}

/** Navigation-only actions - always routed through this single parameter. */
private sealed interface GalaxyBridgeScreenNavIntent {
    data object OnBack : GalaxyBridgeScreenNavIntent
}

/** The Galaxy companies an import may target, with their station names. */
private val companies = listOf(
    "001" to "ΚρήτηTV + Radio 984 (ΙΚΑΡΟΣ)",
    "003" to "Channel 4 (ΚΡΗΤΙΚΗ ΡΑΔΙΟΤΗΛΕΟΡΑΣΗ)",
    "004" to "Σητεία TV",
)

@Composable
private fun GalaxyBridgeScreen(
    state: GalaxyBridgeState,
    onIntent: (GalaxyBridgeIntent) -> Unit,
    onNavIntent: (GalaxyBridgeScreenNavIntent) -> Unit,
    uploadAvailable: Boolean,
    onPickDelivery: () -> Unit,
    onPickDictionary: () -> Unit,
) {
    val status = state.status

    Column(
        modifier = Modifier.fillMaxSize().padding(UIConst.paddingRegular).verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(
                label = Strings[StringKey.COMMON_BACK],
                icon = AppDrawableRepo.arrowBack,
                onClick = { onNavIntent(GalaxyBridgeScreenNavIntent.OnBack) },
            )
            AppText(Strings[StringKey.PREFERENCES_GALAXY_BRIDGE], AppTextStyle.SCREEN_TITLE)
            Spacer(Modifier.weight(1f))
            if (state.running) AppSpinner()
            Spacer(Modifier.width(UIConst.paddingSmall))
            AppText(
                status.state + (statusModeLabel(status)?.let { " · $it" } ?: ""),
                AppTextStyle.BODY_STRONG,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // ── outcome banners ─────────────────────────────────────────────
        if (status.state == "DONE") {
            AppCard(
                Modifier.fillMaxWidth().padding(vertical = UIConst.paddingSmall),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(Modifier.padding(UIConst.paddingRegular)) {
                    AppText(
                        Strings[StringKey.GALAXY_COMPLETE_TITLE],
                        AppTextStyle.ITEM_TITLE,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    AppText(
                        Strings[
                            if (status.mode == "APPLY") StringKey.GALAXY_COMPLETE_APPLIED
                            else StringKey.GALAXY_COMPLETE_DRY
                        ],
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
                        Strings[StringKey.GALAXY_FAILED_TITLE],
                        AppTextStyle.ITEM_TITLE,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    AppText(
                        status.error ?: Strings[StringKey.GALAXY_SEE_LOG],
                        AppTextStyle.BODY,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // ── target: group + company ─────────────────────────────────────
        AppCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(UIConst.paddingRegular),
                verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)
            ) {
                AppText(Strings[StringKey.GALAXY_TARGET_SECTION], AppTextStyle.SECTION_TITLE)
                status.groups.forEach { group ->
                    AppRadioRow(
                        selected = state.selectedGroupId == group.id,
                        onClick = { onIntent(GalaxyBridgeIntent.GroupSelected(group.id)) },
                        label = "${group.name} (${group.schema})",
                    )
                }
                AppText(Strings[StringKey.GALAXY_COMPANY_SECTION], AppTextStyle.ITEM_TITLE)
                companies.forEach { (code, label) ->
                    AppRadioRow(
                        selected = state.selectedCompany == code,
                        onClick = { onIntent(GalaxyBridgeIntent.CompanySelected(code)) },
                        label = "$code — $label",
                    )
                }
            }
        }
        Spacer(Modifier.height(UIConst.paddingCompact))

        // ── files: deliveries + dictionary ──────────────────────────────
        AppCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(UIConst.paddingRegular),
                verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)
            ) {
                AppText(Strings[StringKey.GALAXY_FILES_SECTION], AppTextStyle.SECTION_TITLE)
                if (status.deliveries.isEmpty()) {
                    AppText(Strings[StringKey.GALAXY_NO_DELIVERIES], AppTextStyle.NOTE)
                } else {
                    status.deliveries.forEach { delivery ->
                        AppRadioRow(
                            selected = state.selectedDelivery == delivery.name,
                            onClick = { onIntent(GalaxyBridgeIntent.DeliverySelected(delivery.name)) },
                            label = "${delivery.name} (${delivery.files} ${Strings[StringKey.GALAXY_FILES_COUNT]})",
                        )
                    }
                }
                AppText(
                    Strings[
                        if (status.dictionaryPresent) StringKey.GALAXY_DICTIONARY_OK
                        else StringKey.GALAXY_DICTIONARY_MISSING
                    ],
                    if (status.dictionaryPresent) AppTextStyle.NOTE else AppTextStyle.ERROR_NOTE,
                )
                if (uploadAvailable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
                        AppButton(
                            text = Strings[StringKey.GALAXY_UPLOAD_DELIVERY],
                            onClick = onPickDelivery,
                            enabled = !state.uploadingDelivery && !state.running,
                        )
                        AppButton(
                            text = Strings[StringKey.GALAXY_UPLOAD_DICTIONARY],
                            onClick = onPickDictionary,
                            enabled = !state.uploadingDictionary && !state.running,
                            variant = AppButtonVariant.TEXT,
                        )
                        if (state.uploadingDelivery || state.uploadingDictionary) AppSpinner()
                    }
                } else {
                    AppText(Strings[StringKey.GALAXY_UPLOAD_UNAVAILABLE], AppTextStyle.NOTE)
                }
            }
        }
        Spacer(Modifier.height(UIConst.paddingCompact))

        // ── run ─────────────────────────────────────────────────────────
        AppCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(UIConst.paddingRegular),
                verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)
            ) {
                AppText(Strings[StringKey.GALAXY_RUN_SECTION], AppTextStyle.SECTION_TITLE)
                state.formError?.let { AppText(it.asString(), AppTextStyle.ERROR_NOTE) }
                Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
                    AppButton(
                        text = Strings[StringKey.GALAXY_DRY_RUN],
                        onClick = { onIntent(GalaxyBridgeIntent.RunDryRun) },
                        enabled = state.canRun,
                    )
                    AppButton(
                        text = Strings[StringKey.GALAXY_APPLY],
                        onClick = { onIntent(GalaxyBridgeIntent.AskApply) },
                        enabled = state.canRun,
                        variant = AppButtonVariant.TEXT,
                    )
                    if (status.state in setOf("DONE", "FAILED")) {
                        AppButton(
                            text = Strings[StringKey.GALAXY_RUN_ANOTHER],
                            onClick = { onIntent(GalaxyBridgeIntent.Reset) },
                            variant = AppButtonVariant.TEXT,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(UIConst.paddingCompact))

        // ── results: summary + review list ──────────────────────────────
        status.summary?.let { s ->
            SummaryCard(s)
            Spacer(Modifier.height(UIConst.paddingCompact))
            ReviewCard(state, onIntent)
            Spacer(Modifier.height(UIConst.paddingCompact))
        }

        // ── live progress + log ─────────────────────────────────────────
        if (status.log.isNotEmpty()) {
            AppText(Strings[StringKey.GALAXY_PROGRESS], AppTextStyle.SECTION_TITLE)
            Spacer(Modifier.height(UIConst.paddingExtraSmall))
            status.progress?.let { p ->
                AppProgressBar(
                    fraction = p.fraction,
                    caption = p.label,
                    detail = if (p.total > 0) "${p.done}/${p.total}" else null,
                )
                Spacer(Modifier.height(UIConst.paddingSmall))
            }
            AppLogConsole(lines = status.log)
        }

        // ── APPLY confirmation: writes to a live group database ─────────
        if (state.confirmApply) {
            val group = status.groups.firstOrNull { it.id == state.selectedGroupId }
            AppDialog(
                title = Strings[StringKey.GALAXY_APPLY_CONFIRM_TITLE],
                onDismiss = { onIntent(GalaxyBridgeIntent.DismissApply) },
                confirmText = Strings[StringKey.GALAXY_APPLY],
                onConfirm = { onIntent(GalaxyBridgeIntent.ConfirmApply) },
                dismissText = Strings[StringKey.COMMON_CANCEL],
                destructive = true,
            ) {
                AppText(
                    Strings[StringKey.GALAXY_APPLY_CONFIRM_BODY].withArgs(
                        listOf(group?.schema ?: state.selectedGroupId, state.selectedCompany, state.selectedDelivery)
                    ),
                    AppTextStyle.BODY,
                )
            }
        }
    }
}

/** The importer's counter block, grouped the way the CLI report reads. */
@Composable
private fun SummaryCard(s: GalaxySummary) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(UIConst.paddingRegular),
            verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall)
        ) {
            AppText(Strings[StringKey.GALAXY_SUMMARY], AppTextStyle.SECTION_TITLE)
            AppText(
                Strings[StringKey.GALAXY_SUM_LINES]
                    .withArgs(listOf(s.linesTotal, s.linesCompany, s.docsSeen)),
                AppTextStyle.BODY,
            )
            AppText(
                Strings[StringKey.GALAXY_SUM_PARTIES].withArgs(listOf(
                    s.partiesReferenced, s.partiesAlreadyStamped, s.partiesByCode,
                    s.partiesByVat, s.partiesInserted + s.partiesInsertedBare,
                )),
                AppTextStyle.BODY,
            )
            AppText(
                Strings[StringKey.GALAXY_SUM_ITEMS].withArgs(listOf(
                    s.itemsReferenced, s.itemsAlreadyStamped + s.itemsStamped, s.itemsInserted,
                )),
                AppTextStyle.BODY,
            )
            AppText(
                Strings[StringKey.GALAXY_SUM_TWINS].withArgs(listOf(
                    s.twinDocsSkipped, s.twinRowsSkipped, s.untwinned9010Docs,
                )),
                AppTextStyle.BODY,
            )
            AppText(
                Strings[StringKey.GALAXY_SUM_DOCS].withArgs(listOf(
                    s.docsExamined, s.docsAlreadyKeyed, s.docsMatched,
                    s.docsInserted, s.docLinesInserted, s.docsExcludedFromReports,
                )),
                AppTextStyle.BODY_STRONG,
            )
            AppText(
                Strings[StringKey.GALAXY_SUM_REVIEW].withArgs(listOf(
                    s.docsAmbiguous, s.partiesAmbiguous + s.partiesConflict, s.docsPayerUnresolved,
                )),
                AppTextStyle.BODY,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The review list: what the importer REFUSED to guess. Filter chips per kind;
 * resolution stays manual (v2 will make these actionable).
 */
@Composable
private fun ReviewCard(
    state: GalaxyBridgeState,
    onIntent: (GalaxyBridgeIntent) -> Unit,
) {
    val reviews = state.status.reviews
    AppCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(UIConst.paddingRegular),
            verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)
        ) {
            AppText(
                Strings[StringKey.GALAXY_REVIEW_SECTION].withArgs(listOf(reviews.size)),
                AppTextStyle.SECTION_TITLE,
            )
            if (reviews.isEmpty()) {
                AppText(Strings[StringKey.GALAXY_REVIEW_EMPTY], AppTextStyle.NOTE)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
                ReviewChip(
                    label = Strings[StringKey.GALAXY_REVIEW_ALL],
                    selected = state.reviewFilter == null,
                    onClick = { onIntent(GalaxyBridgeIntent.ReviewFilterChanged(null)) },
                )
                state.reviewKinds.forEach { (kind, count) ->
                    ReviewChip(
                        label = "$kind ($count)",
                        selected = state.reviewFilter == kind,
                        onClick = { onIntent(GalaxyBridgeIntent.ReviewFilterChanged(kind)) },
                    )
                }
            }
            // The list lives inside the screen's single vertical scroll, so a
            // plain Column - a nested LazyColumn would fight the outer scroll.
            state.filteredReviews.forEach { review ->
                Column(Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
                        AppText(review.kind, AppTextStyle.TINY, color = MaterialTheme.colorScheme.primary)
                        AppText(review.key, AppTextStyle.BODY_STRONG)
                    }
                    AppText(review.detail, AppTextStyle.NOTE)
                }
            }
        }
    }
}

@Composable
private fun ReviewChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AppText(
        label,
        if (selected) AppTextStyle.BODY_STRONG else AppTextStyle.BODY,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun statusModeLabel(status: GalaxyStatus): String? = when (status.mode) {
    "APPLY" -> Strings[StringKey.GALAXY_MODE_APPLY]
    "DRY_RUN" -> Strings[StringKey.GALAXY_MODE_DRY]
    else -> null
}

// ═══ Previews ══════════════════════════════════════════════════════════

private val previewGroups = listOf(
    GalaxyGroup(id = "crete-group", name = "Crete Media Group", schema = "commercials_crete_group"),
    GalaxyGroup(id = "channel4", name = "Channel 4", schema = "commercials_channel4"),
)

private val previewDeliveries = listOf(
    GalaxyDelivery(name = "galaxy-2026-07", files = 7, uploadedAtMillis = 0),
    GalaxyDelivery(name = "galaxy-2026-06", files = 7, uploadedAtMillis = 0),
)

private val previewSummary = GalaxySummary(
    linesTotal = 15_894, linesCompany = 10_042, docsSeen = 4_281,
    partiesReferenced = 887, partiesAlreadyStamped = 560, partiesByCode = 56,
    partiesByVat = 269, partiesInserted = 0, partiesAmbiguous = 2, partiesConflict = 41,
    itemsReferenced = 55, itemsAlreadyStamped = 55,
    twinDocsSkipped = 197, twinRowsSkipped = 651, untwinned9010Docs = 111,
    docsExamined = 4_084, docsAlreadyKeyed = 4_019, docsMatched = 0,
    docsInserted = 0, docLinesInserted = 0, docsAmbiguous = 65,
    docsExcludedFromReports = 0,
)

private val previewReviews = listOf(
    GalaxyReview("doc-ambiguous", "001:9001:1", "number 1 + payer 04000007 (2022) matches contracts [3973, 4229] - resolve by hand"),
    GalaxyReview("party-multi-claim", "30000016", "customers.id=44 claimed by Galaxy codes [30000016, 30030653] - resolve by hand"),
    GalaxyReview("untwinned-9010", "001:9010:9", "native Τριγωνικό with no exact Εντολή twin (4 lines) - imported flagged"),
)

/** Ready to run: groups + deliveries + dictionary in place. */
@Preview
@Composable
private fun GalaxyBridgeScreenPreview() = AppPreview(padded = false) {
    GalaxyBridgeScreen(
        state = GalaxyBridgeState(
            status = GalaxyStatus(
                state = "IDLE",
                groups = previewGroups,
                deliveries = previewDeliveries,
                dictionaryPresent = true,
            ),
            selectedGroupId = "crete-group",
            selectedDelivery = "galaxy-2026-07",
        ),
        onIntent = {}, onNavIntent = {}, uploadAvailable = true,
        onPickDelivery = {}, onPickDictionary = {},
    )
}

/** A run in flight: spinner, indeterminate-capable bar, live log. */
@Preview
@Composable
private fun GalaxyBridgeScreenRunningPreview() = AppPreview(padded = false) {
    GalaxyBridgeScreen(
        state = GalaxyBridgeState(
            status = GalaxyStatus(
                state = "RUNNING",
                mode = "DRY_RUN",
                log = listOf(
                    "Galaxy import → group 'crete-group' (commercials_crete_group), company 001",
                    "── Parse Galaxy exports",
                    "flat export: 15894 lines, 10042 for company 001",
                ),
                progress = GalaxyProgress(label = "Reconcile documents", done = 6, total = 9),
                groups = previewGroups,
                deliveries = previewDeliveries,
                dictionaryPresent = true,
            ),
            selectedGroupId = "crete-group",
            selectedDelivery = "galaxy-2026-07",
        ),
        onIntent = {}, onNavIntent = {}, uploadAvailable = true,
        onPickDelivery = {}, onPickDictionary = {},
    )
}

/** The report the operator actually reads: summary + review list. */
@Preview
@Composable
private fun GalaxyBridgeScreenDonePreview() = AppPreview(padded = false) {
    GalaxyBridgeScreen(
        state = GalaxyBridgeState(
            status = GalaxyStatus(
                state = "DONE",
                mode = "DRY_RUN",
                log = listOf("Dry run complete. Nothing was written."),
                summary = previewSummary,
                reviews = previewReviews,
                groups = previewGroups,
                deliveries = previewDeliveries,
                dictionaryPresent = true,
            ),
            selectedGroupId = "crete-group",
            selectedDelivery = "galaxy-2026-07",
        ),
        onIntent = {}, onNavIntent = {}, uploadAvailable = true,
        onPickDelivery = {}, onPickDictionary = {},
    )
}
