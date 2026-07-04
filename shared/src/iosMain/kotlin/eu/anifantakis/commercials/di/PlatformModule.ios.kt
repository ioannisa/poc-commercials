package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.UnsupportedReportService
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

actual val platformModule: Module = module {
    singleOf(::UnsupportedReportService).bind<ReportService>()
}
