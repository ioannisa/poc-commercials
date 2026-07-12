package eu.anifantakis.commercials.feature.timetable.presentation.screens

import androidx.compose.runtime.Composable
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.reports.ui.ReportToolbarLabels
import eu.anifantakis.commercials.reports.ui.ReportToolbarMetrics

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
internal fun reportToolbarLabels() = ReportToolbarLabels(
    preview = Strings[StringKey.REPORT_PREVIEW],
    print = Strings[StringKey.REPORT_PRINT],
    exportPdf = Strings[StringKey.REPORT_EXPORT_PDF],
    notAvailable = Strings[StringKey.REPORT_NOT_AVAILABLE],
)

@Composable
internal fun reportToolbarMetrics(): ReportToolbarMetrics {
    val t = AppTheme.visualTokens
    return ReportToolbarMetrics(
        buttonHeight = t.buttonHeightDense,
        paddingHorizontal = t.buttonPaddingHorizontal,
        paddingVertical = t.buttonPaddingVertical,
        cornerRadius = t.cornerSmall,
        borderWidth = t.controlBorderWidth,
        elevation = t.buttonElevation,
        iconSize = t.iconSmall,
        gap = UIConst.paddingSmall,
    )
}
