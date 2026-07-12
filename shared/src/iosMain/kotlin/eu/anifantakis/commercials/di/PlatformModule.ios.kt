package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.reports.FileKitPdfSink
import eu.anifantakis.commercials.reports.PdfSink
import eu.anifantakis.commercials.reports.ReportApiClient
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.ServerReportService
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

actual val platformModule: Module = module {
    // Reports render server-side (same path as the browsers); the sink turns
    // the bytes into native save/open/share. All five platforms report now.
    singleOf(::ReportApiClient)
    singleOf(::FileKitPdfSink).bind<PdfSink>()
    singleOf(::ServerReportService).bind<ReportService>()
}
