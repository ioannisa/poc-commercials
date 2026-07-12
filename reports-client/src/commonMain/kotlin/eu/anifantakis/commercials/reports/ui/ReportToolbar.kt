package eu.anifantakis.commercials.reports.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
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
 * Control geometry for [ReportToolbar]. Same injection contract as
 * [ReportToolbarLabels]: this is a standalone leaf module that cannot see the
 * app's design system, so the CALLER hands it the platform's visual tokens
 * (`AppTheme.visualTokens`) and the toolbar stops looking like stock Material
 * on a desktop. Defaults reproduce the Material look for standalone use.
 */
@Immutable
data class ReportToolbarMetrics(
    val buttonHeight: Dp = 40.dp,
    val paddingHorizontal: Dp = 16.dp,
    val paddingVertical: Dp = 8.dp,
    val cornerRadius: Dp = 20.dp,
    val borderWidth: Dp = 1.dp,
    /** 0.dp = flat (every pointer platform); only Android floats. */
    val elevation: Dp = 0.dp,
    val iconSize: Dp = 18.dp,
    val gap: Dp = 8.dp,
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
    metrics: ReportToolbarMetrics = ReportToolbarMetrics(),
) {
    val enabled = !busy && available
    val shape = RoundedCornerShape(metrics.cornerRadius)
    val contentPadding = PaddingValues(
        horizontal = metrics.paddingHorizontal,
        vertical = metrics.paddingVertical,
    )
    val sized = Modifier.heightIn(min = metrics.buttonHeight)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(metrics.gap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onPreview,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            border = BorderStroke(metrics.borderWidth, MaterialTheme.colorScheme.outline),
            contentPadding = contentPadding,
        ) {
            Icon(
                ReportIcons.visibility,
                contentDescription = null,   // the label below names the button
                modifier = Modifier.size(metrics.iconSize)
            )
            Spacer(modifier = Modifier.width(metrics.gap / 2))
            Text(labels.preview)
        }

        OutlinedButton(
            onClick = onPrint,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            border = BorderStroke(metrics.borderWidth, MaterialTheme.colorScheme.outline),
            contentPadding = contentPadding,
        ) {
            Icon(
                ReportIcons.print,
                contentDescription = null,
                modifier = Modifier.size(metrics.iconSize)
            )
            Spacer(modifier = Modifier.width(metrics.gap / 2))
            Text(labels.print)
        }

        Button(
            onClick = onExportPdf,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            // null elevation = genuinely FLAT: how every pointer platform gets
            // its look without a per-OS component.
            elevation = if (metrics.elevation > 0.dp)
                ButtonDefaults.buttonElevation(defaultElevation = metrics.elevation) else null,
            contentPadding = contentPadding,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(metrics.iconSize),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    ReportIcons.save,
                    contentDescription = null,
                    modifier = Modifier.size(metrics.iconSize)
                )
            }
            Spacer(modifier = Modifier.width(metrics.gap / 2))
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
