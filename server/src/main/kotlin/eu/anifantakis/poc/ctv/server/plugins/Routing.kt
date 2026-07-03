package eu.anifantakis.poc.ctv.server.plugins

import eu.anifantakis.poc.ctv.server.routes.authRoutes
import eu.anifantakis.poc.ctv.server.routes.demoRoutes
import eu.anifantakis.poc.ctv.server.routes.reportRoutes
import eu.anifantakis.poc.ctv.server.routes.scheduleRoutes
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        // Open endpoints: health checks + login (how you obtain a token)
        get("/") {
            call.respondText("POCCTV Report Server is running")
        }

        get("/health") {
            call.respondText("OK")
        }

        authRoutes()

        // Everything else requires a valid bearer token
        authenticate(AUTH_BEARER) {
            // Report routes
            reportRoutes()

            // Schedule / commercials data (DB-backed)
            scheduleRoutes()

            // Tiny DB connectivity smoke test (returns first row of test.user)
            demoRoutes()
        }
    }
}
