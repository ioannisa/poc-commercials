package eu.anifantakis.poc.ctv.di

import eu.anifantakis.poc.ctv.reports.ReportService
import eu.anifantakis.poc.ctv.reports.UnsupportedReportService
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.bind
import org.koin.plugin.module.dsl.single

actual val platformModule: Module = module {
    single<UnsupportedReportService>().bind(ReportService::class)
}
