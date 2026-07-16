package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.reports.BrowserPdfSink
import eu.anifantakis.commercials.reports.PdfSink
import eu.anifantakis.commercials.reports.ServerReportService
import eu.anifantakis.commercials.reports.ReportApiClient
import eu.anifantakis.commercials.reports.NoStationLogoCache
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.StationLogoCache
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

actual val platformModule: Module = module {
    // Ktor client engine: the JS engine wraps the browser fetch API (OkHttp is
    // JVM-only and cannot run on wasmJs); the browser negotiates HTTP/2/3 itself.
    single<HttpClientEngine> { Js.create() }

    // Browsers generate reports server-side via the report API; the sink is
    // download / new tab / window.print.
    singleOf(::ReportApiClient)
    singleOf(::BrowserPdfSink).bind<PdfSink>()
    singleOf(::ServerReportService).bind<ReportService>()
    // The SERVER stamps the logo on these reports, from its own server.yaml.
    singleOf(::NoStationLogoCache).bind<StationLogoCache>()
}
