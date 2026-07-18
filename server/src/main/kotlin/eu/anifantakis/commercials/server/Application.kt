package eu.anifantakis.commercials.server

import eu.anifantakis.commercials.mcp.di.mcpModule
import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.auth.OAuthDb
import eu.anifantakis.commercials.server.config.ServerConfigLoader
import eu.anifantakis.commercials.server.di.serverModule
import eu.anifantakis.commercials.server.plugins.configureCallLogging
import eu.anifantakis.commercials.server.plugins.configureCompression
import eu.anifantakis.commercials.server.plugins.configureMcp
import eu.anifantakis.commercials.server.plugins.configureRateLimiting
import eu.anifantakis.commercials.server.plugins.configureRouting
import eu.anifantakis.commercials.server.plugins.configureSecurity
import eu.anifantakis.commercials.server.plugins.configureSecurityHeaders
import eu.anifantakis.commercials.server.plugins.configureSerialization
import eu.anifantakis.commercials.server.plugins.configureStatusPages
import eu.anifantakis.commercials.server.plugins.configureCORS
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import eu.anifantakis.commercials.server.scheduler.CentralDb
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main() {
    embeddedServer(
        Netty,
        port = ServerConfigLoader.get().port, // server.port / COMMERCIALS_PORT, default 8080
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    install(Koin) {
        slf4jLogger()
        modules(serverModule, mcpModule)
    }

    val centralDb by inject<CentralDb>()
    val authDb by inject<AuthDb>()
    val oauthDb by inject<OAuthDb>()
    val registry by inject<StationRegistry>()

    // Central auth tables + demo users/grants + YAML super-admin sync.
    // Station schemas bootstrap lazily on first access (StationRegistry.db).
    authDb.bootstrap()
    // OAuth AS tables (clients/codes/tokens) + expired-row sweep.
    oauthDb.bootstrap()
    // Per-user AI-chat token metering (aggregated rows, admin oversight).
    inject<eu.anifantakis.commercials.server.aiusage.AiUsageDb>().value.bootstrap()

    // Only behind a TLS-terminating reverse proxy (server.yaml flag): trust
    // X-Forwarded-* so rate limiting and logs see the real client IP. Never
    // installed without one - the headers are client-spoofable otherwise.
    if (registry.behindReverseProxy) {
        install(XForwardedHeaders)
    }

    configureCallLogging()
    // Before routing: the month grid ships megabytes of repetitive JSON, and
    // this is the biggest single win on that screen (7.79 MB -> 329 KB).
    configureCompression()
    configureStatusPages()
    configureSecurityHeaders()
    configureRateLimiting()
    configureSecurity()
    configureSerialization()
    configureCORS()
    configureRouting()
    configureMcp()   // mounts the MCP server at /mcp (SSE, under bearer auth)

    // Release all DB connection pools when the server shuts down
    monitor.subscribe(ApplicationStopped) {
        registry.closeAll()
        centralDb.close()
    }
}
