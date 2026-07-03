package eu.anifantakis.commercials.reports.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.grids.BreakSlot
import eu.anifantakis.commercials.grids.SchedulerCellData
import eu.anifantakis.commercials.grids.SchedulerKey
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
@Composable
fun ReportToolbar(
    year: Int,
    month: Int,
    breaks: ImmutableList<BreakSlot>,
    cellData: ImmutableMap<SchedulerKey, SchedulerCellData>,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val reportService = koinInject<ReportService>()
    var isLoading by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

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
                ReportResult.Error("No spots in this month")
            } else {
                action(payloads)
            }

            resultMessage = when (result) {
                is ReportResult.Success -> result.filePath?.let { "PDF saved: $it" } ?: result.message
                is ReportResult.Error -> result.message
                is ReportResult.Cancelled -> "Cancelled"
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
                Icons.Default.Visibility,
                contentDescription = "Preview Month Report",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Preview")
        }

        // Print button
        OutlinedButton(
            onClick = { runReportAction { reportService.print(it) } },
            enabled = !isLoading && isReportAvailable
        ) {
            Icon(
                Icons.Default.Print,
                contentDescription = "Print Month Report",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Print")
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
                    Icons.Default.Save,
                    contentDescription = "Export Month to PDF",
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text("Export PDF")
        }

        // Show message if report generation is not available
        if (!isReportAvailable) {
            Text(
                text = "(Reports not available on this platform)",
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
                color = if (message.contains("saved") || message.contains("opened") || message.contains("Print dialog"))
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error
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
            Icons.Default.Print,
            contentDescription = "Generate Report"
        )
    }
}
