package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ProgramFlowReportData
import eu.anifantakis.poc.ctv.reports.models.ReportConfig
import eu.anifantakis.poc.ctv.reports.models.ReportResult

/**
 * Android implementation of ReportService.
 * JasperReports is not available on Android - this returns unsupported messages.
 * For Android, consider using server-side report generation or alternative libraries.
 */
actual class ReportService actual constructor() {

    actual suspend fun exportProgramFlowToPdf(
        reportData: ProgramFlowReportData,
        config: ReportConfig,
        suggestedFileName: String
    ): ReportResult {
        return ReportResult.Error(
            "JasperReports is not available on Android. " +
            "Use server-side report generation instead."
        )
    }

    actual suspend fun previewProgramFlow(
        reportData: ProgramFlowReportData,
        config: ReportConfig
    ): ReportResult {
        return ReportResult.Error(
            "JasperReports is not available on Android. " +
            "Use server-side report generation instead."
        )
    }

    actual suspend fun printProgramFlow(
        reportData: ProgramFlowReportData,
        config: ReportConfig
    ): ReportResult {
        return ReportResult.Error(
            "JasperReports is not available on Android. " +
            "Use server-side report generation instead."
        )
    }

    actual fun isJasperReportsAvailable(): Boolean = false
}

actual fun createReportService(): ReportService = ReportService()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
