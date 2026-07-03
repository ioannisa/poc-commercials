package eu.anifantakis.commercials.server

import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.config.ServerConfigLoader
import eu.anifantakis.commercials.server.di.serverModule
import eu.anifantakis.commercials.server.plugins.configureCallLogging
import eu.anifantakis.commercials.server.plugins.configureRouting
import eu.anifantakis.commercials.server.plugins.configureSecurity
import eu.anifantakis.commercials.server.plugins.configureSerialization
import eu.anifantakis.commercials.server.plugins.configureStatusPages
import eu.anifantakis.commercials.server.plugins.configureCORS
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
        port = ServerConfigLoader.get().port, // server.port / POC_PORT, default 8080
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    install(Koin) {
        slf4jLogger()
        modules(serverModule)
    }

    val centralDb by inject<CentralDb>()
    val authDb by inject<AuthDb>()
    val registry by inject<StationRegistry>()

    // Central auth tables + demo users/grants for the hosted stations.
    // Station schemas bootstrap lazily on first access (StationRegistry.db).
    authDb.bootstrap(registry.ids)

    configureCallLogging()
    configureStatusPages()
    configureSecurity()
    configureSerialization()
    configureCORS()
    configureRouting()

    // Release all DB connection pools when the server shuts down
    monitor.subscribe(ApplicationStopped) {
        registry.closeAll()
        centralDb.close()
    }
}
