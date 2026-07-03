package eu.anifantakis.ctv.di

import eu.anifantakis.ctv.reports.BrowserReportService
import eu.anifantakis.ctv.reports.ReportApiClient
import eu.anifantakis.ctv.reports.ReportService
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.bind
import org.koin.plugin.module.dsl.single

actual val platformModule: Module = module {
    // Browsers generate reports server-side via the report API.
    //
    // Definition style note (empirically verified against plugin 1.0.1):
    //   single<Impl>().bind(Interface::class)      <- full compile-time checks
    //   single<Interface> { create(::Impl) }       <- visible, but ctor deps NOT validated
    //   single<Interface> { new(::Impl) }          <- invisible to the checker (build fails
    //                                                  at koinInject sites with KOIN-D002)
    single<ReportApiClient>()
    single<BrowserReportService>().bind(ReportService::class)
}
