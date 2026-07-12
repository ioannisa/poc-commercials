package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.reports.BrowserPdfSink
import eu.anifantakis.commercials.reports.PdfSink
import eu.anifantakis.commercials.reports.ServerReportService
import eu.anifantakis.commercials.reports.ReportApiClient
import eu.anifantakis.commercials.reports.ReportService
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

actual val platformModule: Module = module {
    // Browsers generate reports server-side via the report API; the sink is
    // download / new tab / window.print.
    singleOf(::ReportApiClient)
    singleOf(::BrowserPdfSink).bind<PdfSink>()
    singleOf(::ServerReportService).bind<ReportService>()
}
