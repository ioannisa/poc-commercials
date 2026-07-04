package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.reports.DesktopReportService
import eu.anifantakis.commercials.reports.ReportService
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

actual val platformModule: Module = module {
    // Desktop generates reports in-process with the embedded Jasper engine
    singleOf(::DesktopReportService).bind<ReportService>()
}
