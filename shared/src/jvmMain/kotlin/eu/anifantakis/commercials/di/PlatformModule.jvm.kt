package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.reports.DesktopPdfSink
import eu.anifantakis.commercials.reports.DesktopReportService
import eu.anifantakis.commercials.reports.PdfSink
import eu.anifantakis.commercials.reports.DesktopStationLogoCache
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.StationLogoCache
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

actual val platformModule: Module = module {
    // Ktor client engine, per-platform (kmp-developer: the HttpClient config
    // stays engine-agnostic in CommonHttpClient; only the engine is bound here).
    // OkHttp on desktop: battle-tested connection pooling + HTTP/2 over TLS.
    // OkHttp-specific tuning goes in the config{} seam below.
    single<HttpClientEngine> {
        OkHttp.create {
            config {
                retryOnConnectionFailure(true)
                // e.g. protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)) for h2 over TLS,
                //      connectionPool(...), addInterceptor(...) as needed.
            }
        }
    }

    // Desktop generates reports in-process with the embedded Jasper engine
    singleOf(::DesktopReportService).bind<ReportService>()
    // Server-rendered bytes (AI-chat out-of-band reports): temp file + system viewer.
    singleOf(::DesktopPdfSink).bind<PdfSink>()
    // ...so Jasper needs the logo as a LOCAL file: fetch the bytes, cache them.
    // (Every other platform renders server-side, where the server applies its
    // own logo and a client-supplied path is refused.)
    singleOf(::DesktopStationLogoCache).bind<StationLogoCache>()
}
