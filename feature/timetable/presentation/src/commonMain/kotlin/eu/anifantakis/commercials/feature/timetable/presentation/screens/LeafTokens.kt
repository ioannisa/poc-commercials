package eu.anifantakis.commercials.feature.timetable.presentation.screens

import androidx.compose.runtime.Composable
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.reports.ui.ReportToolbarLabels
import eu.anifantakis.commercials.reports.ui.ReportToolbarMetrics
import androidx.compose.runtime.remember
import eu.anifantakis.commercials.core.presentation.design_system.PlatformVisualTokens
import eu.anifantakis.commercials.core.presentation.string_resources.LocalLanguage
import eu.anifantakis.commercials.core.presentation.string_resources.localized

/**
 * The bridge to the LEAF toolkits (`:reports-client`, `:core:presentation:grids`).
 *
 * Those modules deliberately take NO dependency on the app's design system or
 * localization, so everything they need is INJECTED: labels from the StringKey
 * system, control geometry from the platform's visual tokens. This module is
 * the only one that talks to both leaves, so the mappers live here once -
 * shared by the timetable screen (month report) and the commercial-detail
 * screen (this break's report).
 *
 * Forget the metrics and the toolbar silently renders stock Material next to
 * platform-token chrome; an ArchitectureTest rule now fails the build for it.
 */
@Composable
internal fun reportToolbarLabels(): ReportToolbarLabels {
    // Remembered against the language - these are read in screen root scopes,
    // which re-execute on every state tick.
    val language = LocalLanguage.current
    return remember(language) {
        ReportToolbarLabels(
            preview = StringKey.REPORT_PREVIEW.localized(),
            print = StringKey.REPORT_PRINT.localized(),
            exportPdf = StringKey.REPORT_EXPORT_PDF.localized(),
            notAvailable = StringKey.REPORT_NOT_AVAILABLE.localized(),
        )
    }
}

@Composable
internal fun reportToolbarMetrics(): ReportToolbarMetrics {
    val t = AppTheme.visualTokens
    // The tokens are fixed for the session; one instance, not one per tick.
    return remember(t) { reportToolbarMetricsFor(t) }
}

private fun reportToolbarMetricsFor(t: PlatformVisualTokens): ReportToolbarMetrics =
    ReportToolbarMetrics(
        buttonHeight = t.buttonHeightDense,
        paddingHorizontal = t.buttonPaddingHorizontal,
        paddingVertical = t.buttonPaddingVertical,
        cornerRadius = t.cornerSmall,
        borderWidth = t.controlBorderWidth,
        elevation = t.buttonElevation,
        iconSize = t.iconSmall,
        gap = UIConst.paddingSmall,
    )
