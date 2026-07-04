package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.reports.BrowserReportService
import eu.anifantakis.commercials.reports.ReportApiClient
import eu.anifantakis.commercials.reports.ReportService
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

actual val platformModule: Module = module {
    // Browsers generate reports server-side via the report API.
    singleOf(::ReportApiClient)
    singleOf(::BrowserReportService).bind<ReportService>()
}
