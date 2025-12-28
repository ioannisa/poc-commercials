package eu.anifantakis.poc.ctv.server.plugins

import eu.anifantakis.poc.ctv.server.routes.reportRoutes
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        // Health check endpoint
        get("/") {
            call.respondText("POCCTV Report Server is running")
        }

        get("/health") {
            call.respondText("OK")
        }

        // Report routes
        reportRoutes()
    }
}
