package eu.anifantakis.commercials.reports.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.grids.BreakSlot
import eu.anifantakis.commercials.core.presentation.grids.SchedulerCellData
import eu.anifantakis.commercials.core.presentation.grids.SchedulerKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import eu.anifantakis.commercials.reports.ReportDataFactory
import eu.anifantakis.commercials.reports.ReportPayload
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.models.ReportConfig
import eu.anifantakis.commercials.reports.models.ReportResult
import eu.anifantakis.commercials.reports.toReportPayload
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Toolbar with report generation buttons for the visible month.
 * Preview, Print, and Export PDF all produce the ENTIRE month - one daily
 * Program Flow report per day that has spots, merged into one document.
 */
/**
 * Display labels for [ReportToolbar]. reports-client is a standalone module
 * (no dependency on the app's localization), so callers inject localized
 * labels; English defaults keep it usable standalone.
 */
@Immutable
data class ReportToolbarLabels(
    val preview: String = "Preview",
    val print: String = "Print",
    val exportPdf: String = "Export PDF",
    val noSpots: String = "No spots in this month",
    val pdfSavedPrefix: String = "PDF saved: ",
    val cancelled: String = "Cancelled",
    val notAvailable: String = "(Reports not available on this platform)",
)

@Composable
fun ReportToolbar(
    year: Int,
    month: Int,
    breaks: ImmutableList<BreakSlot>,
    cellData: ImmutableMap<SchedulerKey, SchedulerCellData>,
    modifier: Modifier = Modifier,
    labels: ReportToolbarLabels = ReportToolbarLabels(),
) {
    val scope = rememberCoroutineScope()
    val reportService = koinInject<ReportService>()
    var isLoading by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }

    val isReportAvailable = reportService.isReportGenerationAvailable()

    fun monthPayloads(): List<ReportPayload> =
        ReportDataFactory.createMonthProgramFlowData(year, month, breaks, cellData)
            .map { it.toReportPayload(ReportConfig()) }

    fun runReportAction(action: suspend (List<ReportPayload>) -> ReportResult) {
        scope.launch {
            isLoading = true
            resultMessage = null

            val payloads = monthPayloads()
            val result = if (payloads.isEmpty()) {
                ReportResult.Error(labels.noSpots)
            } else {
                action(payloads)
            }

            resultOk = result is ReportResult.Success
            resultMessage = when (result) {
                is ReportResult.Success -> result.filePath?.let { labels.pdfSavedPrefix + it } ?: result.message
                is ReportResult.Error -> result.message
                is ReportResult.Cancelled -> labels.cancelled
            }

            isLoading = false
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Preview button
        OutlinedButton(
            onClick = { runReportAction { reportService.preview(it) } },
            enabled = !isLoading && isReportAvailable
        ) {
            Icon(
                ReportIcons.visibility,
                contentDescription = labels.preview,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(labels.preview)
        }

        // Print button
        OutlinedButton(
            onClick = { runReportAction { reportService.print(it) } },
            enabled = !isLoading && isReportAvailable
        ) {
            Icon(
                ReportIcons.print,
                contentDescription = labels.print,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(labels.print)
        }

        // Export PDF button
        Button(
            onClick = {
                val fileName = "ProgramFlow_${year}-${month.toString().padStart(2, '0')}.pdf"
                runReportAction { reportService.exportToPdf(it, fileName) }
            },
            enabled = !isLoading && isReportAvailable
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    ReportIcons.save,
                    contentDescription = labels.exportPdf,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(labels.exportPdf)
        }

        // Show message if report generation is not available
        if (!isReportAvailable) {
            Text(
                text = labels.notAvailable,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Result message
        resultMessage?.let { message ->
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (resultOk) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Compact report button for use in toolbars
 */
@Composable
fun ReportIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
    ) {
        Icon(
            ReportIcons.print,
            contentDescription = "Generate Report"
        )
    }
}
