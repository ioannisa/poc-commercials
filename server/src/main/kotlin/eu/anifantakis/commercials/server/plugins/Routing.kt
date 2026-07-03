package eu.anifantakis.commercials.server.plugins

import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.routes.authRoutes
import eu.anifantakis.commercials.server.routes.demoRoutes
import eu.anifantakis.commercials.server.routes.reportRoutes
import eu.anifantakis.commercials.server.routes.scheduleRoutes
import eu.anifantakis.commercials.server.scheduler.SchedulerDb
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    // Injected once here and passed to the route builders explicitly -
    // route functions stay plain and easy to test with fakes.
    val schedulerDb by inject<SchedulerDb>()
    val authDb by inject<AuthDb>()

    routing {
        // Open endpoints: health checks + login (how you obtain a token)
        get("/") {
            call.respondText("POCCTV Report Server is running")
        }

        get("/health") {
            call.respondText("OK")
        }

        authRoutes(authDb)

        // Everything else requires a valid bearer token
        authenticate(AUTH_BEARER) {
            // Report routes
            reportRoutes()

            // Schedule / commercials data (DB-backed)
            scheduleRoutes(schedulerDb)

            // Tiny DB connectivity smoke test (returns first row of test.user)
            demoRoutes(schedulerDb)
        }
    }
}
