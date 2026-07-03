package eu.anifantakis.poc.ctv.di

import eu.anifantakis.poc.ctv.reports.DesktopReportService
import eu.anifantakis.poc.ctv.reports.ReportService
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.bind
import org.koin.plugin.module.dsl.single

actual val platformModule: Module = module {
    // Desktop generates reports in-process with the embedded Jasper engine
    single<DesktopReportService>().bind(ReportService::class)
}
