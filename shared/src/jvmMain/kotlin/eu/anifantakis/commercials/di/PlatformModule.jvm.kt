package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.reports.DesktopReportService
import eu.anifantakis.commercials.reports.DesktopStationLogoCache
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.StationLogoCache
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

actual val platformModule: Module = module {
    // Desktop generates reports in-process with the embedded Jasper engine
    singleOf(::DesktopReportService).bind<ReportService>()
    // ...so Jasper needs the logo as a LOCAL file: fetch the bytes, cache them.
    // (Every other platform renders server-side, where the server applies its
    // own logo and a client-supplied path is refused.)
    singleOf(::DesktopStationLogoCache).bind<StationLogoCache>()
}
