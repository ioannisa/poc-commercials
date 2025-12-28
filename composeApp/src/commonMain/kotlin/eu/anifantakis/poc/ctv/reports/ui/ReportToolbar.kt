package eu.anifantakis.poc.ctv.reports.ui

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
import eu.anifantakis.poc.ctv.grids.BreakSlot
import eu.anifantakis.poc.ctv.grids.SchedulerCellData
import eu.anifantakis.poc.ctv.grids.SchedulerKey
import eu.anifantakis.poc.ctv.grids.StableDate
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import eu.anifantakis.poc.ctv.reports.ReportDataFactory
import eu.anifantakis.poc.ctv.reports.createReportService
import eu.anifantakis.poc.ctv.reports.models.ReportConfig
import eu.anifantakis.poc.ctv.reports.models.ReportResult
import kotlinx.coroutines.launch

/**
 * Toolbar with report generation buttons.
 * Shows Preview, Print, and Export PDF buttons when JasperReports is available.
 */
@Composable
fun ReportToolbar(
    selectedDate: StableDate,
    breaks: ImmutableList<BreakSlot>,
    cellData: ImmutableMap<SchedulerKey, SchedulerCellData>,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val reportService = remember { createReportService() }
    var isLoading by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val isJasperAvailable = reportService.isJasperReportsAvailable()

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Preview button
        OutlinedButton(
            onClick = {
                scope.launch {
                    isLoading = true
                    resultMessage = null

                    val reportData = ReportDataFactory.createProgramFlowData(
                        date = selectedDate.value,
                        breaks = breaks,
                        cellData = cellData
                    )

                    val result = reportService.previewProgramFlow(
                        reportData = reportData,
                        config = ReportConfig()
                    )

                    resultMessage = when (result) {
                        is ReportResult.Success -> result.message
                        is ReportResult.Error -> result.message
                        is ReportResult.Cancelled -> "Cancelled"
                    }

                    isLoading = false
                }
            },
            enabled = !isLoading && isJasperAvailable
        ) {
            Icon(
                Icons.Default.Visibility,
                contentDescription = "Preview Report",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Preview")
        }

        // Print button
        OutlinedButton(
            onClick = {
                scope.launch {
                    isLoading = true
                    resultMessage = null

                    val reportData = ReportDataFactory.createProgramFlowData(
                        date = selectedDate.value,
                        breaks = breaks,
                        cellData = cellData
                    )

                    val result = reportService.printProgramFlow(
                        reportData = reportData,
                        config = ReportConfig()
                    )

                    resultMessage = when (result) {
                        is ReportResult.Success -> result.message
                        is ReportResult.Error -> result.message
                        is ReportResult.Cancelled -> "Cancelled"
                    }

                    isLoading = false
                }
            },
            enabled = !isLoading && isJasperAvailable
        ) {
            Icon(
                Icons.Default.Print,
                contentDescription = "Print Report",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Print")
        }

        // Export PDF button
        Button(
            onClick = {
                scope.launch {
                    isLoading = true
                    resultMessage = null

                    val reportData = ReportDataFactory.createProgramFlowData(
                        date = selectedDate.value,
                        breaks = breaks,
                        cellData = cellData
                    )

                    val fileName = "ProgramFlow_${selectedDate.value}.pdf"

                    val result = reportService.exportProgramFlowToPdf(
                        reportData = reportData,
                        config = ReportConfig(),
                        suggestedFileName = fileName
                    )

                    resultMessage = when (result) {
                        is ReportResult.Success -> "PDF saved: ${result.filePath ?: "Success"}"
                        is ReportResult.Error -> result.message
                        is ReportResult.Cancelled -> "Export cancelled"
                    }

                    isLoading = false
                }
            },
            enabled = !isLoading && isJasperAvailable
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Default.Save,
                    contentDescription = "Export to PDF",
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text("Export PDF")
        }

        // Show message if JasperReports not available
        if (!isJasperAvailable) {
            Text(
                text = "(JasperReports: Desktop only)",
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
