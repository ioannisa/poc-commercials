package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ProgramFlowReportData
import eu.anifantakis.poc.ctv.reports.models.ReportConfig
import eu.anifantakis.poc.ctv.reports.models.ReportResult
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS implementation of ReportService.
 * JasperReports is not available on iOS - this returns unsupported messages.
 * For iOS, consider using server-side report generation.
 */
actual class ReportService actual constructor() {

    actual suspend fun exportProgramFlowToPdf(
        reportData: ProgramFlowReportData,
        config: ReportConfig,
        suggestedFileName: String
    ): ReportResult {
        return ReportResult.Error(
            "JasperReports is not available on iOS. " +
            "Use server-side report generation instead."
        )
    }

    actual suspend fun previewProgramFlow(
        reportData: ProgramFlowReportData,
        config: ReportConfig
    ): ReportResult {
        return ReportResult.Error(
            "JasperReports is not available on iOS. " +
            "Use server-side report generation instead."
        )
    }

    actual suspend fun printProgramFlow(
        reportData: ProgramFlowReportData,
        config: ReportConfig
    ): ReportResult {
        return ReportResult.Error(
            "JasperReports is not available on iOS. " +
            "Use server-side report generation instead."
        )
    }

    actual fun isJasperReportsAvailable(): Boolean = false
}

actual fun createReportService(): ReportService = ReportService()

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
