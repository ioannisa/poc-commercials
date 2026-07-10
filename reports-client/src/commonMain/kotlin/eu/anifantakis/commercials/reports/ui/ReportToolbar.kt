package eu.anifantakis.commercials.reports.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Display labels for [ReportToolbar]. reports-client is a standalone module
 * (no dependency on the app's localization), so callers inject localized
 * labels; English defaults keep it usable standalone.
 *
 * Only BUTTON text lives here. What a report OUTCOME says ("no spots",
 * "cancelled", "PDF saved: …") is the caller's message, delivered wherever
 * that app shows results - in this app, the one global snackbar.
 */
@Immutable
data class ReportToolbarLabels(
    val preview: String = "Preview",
    val print: String = "Print",
    val exportPdf: String = "Export PDF",
    val notAvailable: String = "(Reports not available on this platform)",
)

/**
 * Preview / Print / Export PDF over whatever the caller decides to report on.
 *
 * STATELESS on purpose. This module knows how to BUILD a report
 * ([eu.anifantakis.commercials.reports.ReportDataFactory]) and how to RUN one
 * ([eu.anifantakis.commercials.reports.ReportService]) - but a toolbar is the
 * place to do neither. It renders three buttons and reports clicks; the
 * calling ViewModel owns the payload, the service and the outcome. That also
 * keeps this leaf module free of Koin.
 *
 * @param busy an action is running - the buttons wait for it
 * @param available this platform can generate reports at all
 */
@Composable
fun ReportToolbar(
    onPreview: () -> Unit,
    onPrint: () -> Unit,
    onExportPdf: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    available: Boolean = true,
    labels: ReportToolbarLabels = ReportToolbarLabels(),
) {
    val enabled = !busy && available

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = onPreview, enabled = enabled) {
            Icon(
                ReportIcons.visibility,
                contentDescription = labels.preview,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(labels.preview)
        }

        OutlinedButton(onClick = onPrint, enabled = enabled) {
            Icon(
                ReportIcons.print,
                contentDescription = labels.print,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(labels.print)
        }

        Button(onClick = onExportPdf, enabled = enabled) {
            if (busy) {
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

        if (!available) {
            Text(
                text = labels.notAvailable,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
