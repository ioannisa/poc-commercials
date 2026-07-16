package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.reports.FileKitPdfSink
import eu.anifantakis.commercials.reports.PdfSink
import eu.anifantakis.commercials.reports.ReportApiClient
import eu.anifantakis.commercials.reports.NoStationLogoCache
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.StationLogoCache
import eu.anifantakis.commercials.reports.ServerReportService
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

actual val platformModule: Module = module {
    // Ktor client engine, per-platform (kmp-developer: the HttpClient config
    // stays engine-agnostic in CommonHttpClient; only the engine is bound here).
    single<HttpClientEngine> {
        OkHttp.create {
            config {
                retryOnConnectionFailure(true)
            }
        }
    }

    // Reports render server-side (same path as the browsers); the sink turns
    // the bytes into native save/open/share. All five platforms report now.
    singleOf(::ReportApiClient)
    singleOf(::FileKitPdfSink).bind<PdfSink>()
    singleOf(::ServerReportService).bind<ReportService>()
    // The SERVER stamps the logo on these reports, from its own server.yaml.
    singleOf(::NoStationLogoCache).bind<StationLogoCache>()
}
