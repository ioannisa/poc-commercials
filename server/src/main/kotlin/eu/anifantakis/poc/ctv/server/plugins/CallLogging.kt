package eu.anifantakis.poc.ctv.server.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import org.slf4j.event.Level

/**
 * One INFO line per handled request (method, path, status, duration).
 * The dependency was already on the classpath; it just was never installed.
 */
fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.INFO
    }
}
