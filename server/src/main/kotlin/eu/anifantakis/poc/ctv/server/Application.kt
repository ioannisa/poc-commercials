package eu.anifantakis.poc.ctv.server

import eu.anifantakis.poc.ctv.server.auth.AuthDb
import eu.anifantakis.poc.ctv.server.config.ServerConfigLoader
import eu.anifantakis.poc.ctv.server.di.serverModule
import eu.anifantakis.poc.ctv.server.plugins.configureCallLogging
import eu.anifantakis.poc.ctv.server.plugins.configureRouting
import eu.anifantakis.poc.ctv.server.plugins.configureSecurity
import eu.anifantakis.poc.ctv.server.plugins.configureSerialization
import eu.anifantakis.poc.ctv.server.plugins.configureStatusPages
import eu.anifantakis.poc.ctv.server.plugins.configureCORS
import eu.anifantakis.poc.ctv.server.scheduler.SchedulerDb
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

    val schedulerDb by inject<SchedulerDb>()
    val authDb by inject<AuthDb>()

    schedulerDb.bootstrap()
    authDb.bootstrap()

    configureCallLogging()
    configureStatusPages()
    configureSecurity()
    configureSerialization()
    configureCORS()
    configureRouting()

    // Release the DB connection pool when the server shuts down
    monitor.subscribe(ApplicationStopped) {
        schedulerDb.close()
    }
}
