package eu.anifantakis.commercials.server.plugins

import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.routes.adminRoutes
import eu.anifantakis.commercials.migration.MigrationService
import eu.anifantakis.commercials.migration.migrationRoutes
import eu.anifantakis.commercials.server.plugins.requireAdmin
import eu.anifantakis.commercials.server.routes.authRoutes
import eu.anifantakis.commercials.server.routes.emailRoutes
import eu.anifantakis.commercials.server.routes.stationAdminRoutes
import eu.anifantakis.commercials.server.routes.demoRoutes
import eu.anifantakis.commercials.server.routes.reportRoutes
import eu.anifantakis.commercials.server.routes.scheduleRoutes
import eu.anifantakis.commercials.server.scheduler.CentralDb
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.OpenApiDocSource
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    // Injected once here and passed to the route builders explicitly -
    // route functions stay plain and easy to test with fakes.
    val centralDb by inject<CentralDb>()
    val authDb by inject<AuthDb>()
    val registry by inject<StationRegistry>()
    val migrationService by inject<MigrationService>()

    routing {
        // Interactive API docs at /swagger, rendered from the compiler-generated
        // OpenAPI spec (ktor { openApi { enabled } } in build.gradle.kts). Gated by
        // the `swagger` flag in server.yaml (default off) - a per-deployment toggle,
        // independent of developmentMode, so the super-admin "API Docs" link works
        // wherever it is turned on. NOTE: when enabled this endpoint is
        // UNAUTHENTICATED (a browser navigation carries no bearer), so the full API
        // SHAPE - including admin routes - is browsable by anyone who can reach the
        // server; executing any authenticated route still requires a valid token.
        // Covers REST only; /mcp (JSON-RPC over SSE) is outside OpenAPI's scope.
        if (registry.swaggerEnabled) {
            swaggerUI("swagger") {
                info = OpenApiInfo("Commercials Manager API", "1.0.0")
                source = OpenApiDocSource.Routing()
            }
        }

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
            adminRoutes(authDb, registry)
            migrationRoutes(migrationService, requireAdmin = { requireAdmin() })
            stationAdminRoutes(registry, authDb)

            // Customer schedule emails (staff only)
            emailRoutes(registry)

            // Report routes
            reportRoutes(registry)

            // Schedule / commercials data (station-scoped, DB-backed)
            scheduleRoutes(registry)

            // Tiny DB connectivity smoke test (returns first row of test.user)
            demoRoutes(centralDb)
        }
    }
}
