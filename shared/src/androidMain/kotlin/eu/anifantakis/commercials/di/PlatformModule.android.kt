package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.UnsupportedReportService
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.bind
import org.koin.plugin.module.dsl.single

actual val platformModule: Module = module {
    single<UnsupportedReportService>().bind(ReportService::class)
}
