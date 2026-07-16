package eu.anifantakis.commercials.mcp.di

import eu.anifantakis.commercials.mcp.EmailSender
import eu.anifantakis.commercials.mcp.FileReportStore
import eu.anifantakis.commercials.mcp.JasperReportRenderer
import eu.anifantakis.commercials.mcp.McpToolServices
import eu.anifantakis.commercials.mcp.ReportRenderer
import eu.anifantakis.commercials.mcp.ReportStore
import eu.anifantakis.commercials.mcp.SmtpEmailSender
import eu.anifantakis.commercials.mcp.StationDirectory
import eu.anifantakis.commercials.mcp.StationRegistryDirectory
import eu.anifantakis.commercials.mcp.mcpMutationsEnabled
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * The MCP tool backend's Koin bindings — one file, one module, per the DI
 * conventions.
 *
 * It lives in `:mcp` rather than in the server's `di/` package because the tool
 * backend is transport-owned here: the Ktor server (which mounts `/mcp` over SSE)
 * loads it and supplies the
 * [StationRegistry][eu.anifantakis.commercials.server.stations.StationRegistry]
 * these bindings resolve with `get()`.
 *
 * Every port is bound to its adapter (the one class allowed to touch that kind of
 * I/O), so a test can swap any of them for a fake. Constructor-reference `*Of`
 * forms where the graph supplies the arguments; lambdas only where a VALUE is
 * passed (the report output dir, the env-read mutation kill switch).
 */
val mcpModule = module {
    // ports -> adapters (the only classes that touch JDBC / Jasper / disk / SMTP)
    singleOf(::StationRegistryDirectory) { bind<StationDirectory>() }
    singleOf(::JasperReportRenderer) { bind<ReportRenderer>() }
    singleOf(::SmtpEmailSender) { bind<EmailSender>() }
    single<ReportStore> { FileReportStore() }

    // the organizer
    single<McpToolServices> {
        McpToolServices(
            directory = get(),
            renderer = get(),
            store = get(),
            emailSender = get(),
            mutationsEnabled = mcpMutationsEnabled(),
        )
    }
}
