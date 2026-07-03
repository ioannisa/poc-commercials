package eu.anifantakis.commercials.server.plugins

import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.routes.adminRoutes
import eu.anifantakis.commercials.server.migration.MigrationService
import eu.anifantakis.commercials.server.routes.authRoutes
import eu.anifantakis.commercials.server.routes.migrationRoutes
import eu.anifantakis.commercials.server.routes.stationAdminRoutes
import eu.anifantakis.commercials.server.routes.demoRoutes
import eu.anifantakis.commercials.server.routes.reportRoutes
import eu.anifantakis.commercials.server.routes.scheduleRoutes
import eu.anifantakis.commercials.server.scheduler.CentralDb
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    // Injected once here and passed to the route builders explicitly -
    // route functions stay plain and easy to test with fakes.
    val centralDb by inject<CentralDb>()
    val authDb by inject<AuthDb>()
    val registry by inject<StationRegistry>()
    val migrationService by inject<MigrationService>()

    routing {
        // Open endpoints: health checks + login (how you obtain a token)
        get("/") {
            call.respondText("Commercials Manager Server is running")
        }

        get("/health") {
            call.respondText("OK")
        }

        authRoutes(authDb, registry)

        // Everything else requires a valid bearer token
        authenticate(AUTH_BEARER) {
            // User management + legacy migration (super administrator only)
            adminRoutes(authDb)
            migrationRoutes(migrationService)
            stationAdminRoutes(registry, authDb)

            // Report routes
            reportRoutes()

            // Schedule / commercials data (station-scoped, DB-backed)
            scheduleRoutes(registry)

            // Tiny DB connectivity smoke test (returns first row of test.user)
            demoRoutes(centralDb)
        }
    }
}
