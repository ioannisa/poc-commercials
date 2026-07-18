package eu.anifantakis.commercials.server.plugins

import eu.anifantakis.commercials.mcp.McpToolServices
import eu.anifantakis.commercials.server.ai.AiChatService
import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.auth.OAuthDb
import eu.anifantakis.commercials.server.routes.adminRoutes
import eu.anifantakis.commercials.server.routes.aiRoutes
import eu.anifantakis.commercials.migration.MigrationService
import eu.anifantakis.commercials.migration.migrationRoutes
import eu.anifantakis.commercials.server.routes.authRoutes
import eu.anifantakis.commercials.server.routes.oAuthRoutes
import eu.anifantakis.commercials.server.routes.emailRoutes
import eu.anifantakis.commercials.server.routes.stationAdminRoutes
import eu.anifantakis.commercials.server.routes.demoRoutes
import eu.anifantakis.commercials.server.routes.reportRoutes
import eu.anifantakis.commercials.server.routes.scheduleRoutes
import eu.anifantakis.commercials.server.scheduler.CentralDb
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.http.ContentType
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.Tag
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.OpenApiDocSource
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

/**
 * Document-level tag descriptions (the text under each section heading in Swagger
 * UI). Ktor's config DSL can only NAME tags, so the descriptions are injected into
 * the assembled OpenApiDoc via the source's serializeModel hook (see swaggerUI
 * below). Names must match the per-endpoint `Tag:` KDoc values exactly.
 */
private val API_TAGS = listOf(
    Tag("Auth", "Login, logout, password reset, and the session heartbeat."),
    Tag("MCP", "Personal access tokens for MCP/agent clients, and the global MCP switch."),
    Tag("Admin", "Super-admin management of users, station grants, and hosted stations."),
    Tag("Emails", "Customer schedule emails: search, preview, send, and the audit log."),
    Tag("Schedule", "Commercials schedule: breaks, placements, and the spot finder."),
    Tag("Reports", "PDF report generation (single and batch) and the station report logo."),
    Tag("Migration", "Legacy-dump migration console: browse, start, flow-map, and reset."),
    Tag("Health", "Liveness and database-connectivity probes."),
)

private val SPEC_JSON = Json { encodeDefaults = false; explicitNulls = false }

fun Application.configureRouting() {
    // Injected once here and passed to the route builders explicitly -
    // route functions stay plain and easy to test with fakes.
    val centralDb by inject<CentralDb>()
    val authDb by inject<AuthDb>()
    val oauthDb by inject<OAuthDb>()
    val registry by inject<StationRegistry>()
    val migrationService by inject<MigrationService>()
    val mcpToolServices by inject<McpToolServices>()
    val aiChatService = AiChatService(registry, mcpToolServices)

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
                // Inject the group (tag) descriptions the config DSL can't express:
                // serialize the assembled doc ourselves after stamping API_TAGS on it.
                source = OpenApiDocSource.Routing(
                    contentType = ContentType.Application.Json,
                    serializeModel = { doc ->
                        SPEC_JSON.encodeToString(OpenApiDoc.serializer(), doc.copy(tags = API_TAGS))
                    },
                )
            }
        }

        // Open endpoints: health checks + login (how you obtain a token)
        /**
         * Report that the Commercials Manager server is running (root liveness check).
         *
         * Tag: Health
         */
        get("/") {
            call.respondText("Commercials Manager Server is running")
        }

        /**
         * Return a plain OK response for load-balancer and uptime health probes.
         *
         * Tag: Health
         */
        get("/health") {
            call.respondText("OK")
        }

        authRoutes(authDb, oauthDb, registry)

        // The built-in OAuth 2.1 AS for native MCP connectors - mounted only
        // when server.yaml sets publicBaseUrl (it is the issuer). Open routes
        // by protocol design: discovery + registration + the login page.
        if (registry.publicBaseUrl != null) {
            oAuthRoutes(oauthDb, authDb, registry)
        }

        // Everything else requires a valid bearer token
        authenticate(AUTH_BEARER) {
            // A pending OAuth grant resolves but must not reach any data route.
            install(PendingOAuthGate)

            // In-app AI assistant - mounted only when server.yaml configures `ai:`.
            if (aiChatService.enabled) {
                aiRoutes(aiChatService)
            }
            // User management + legacy migration (super administrator only)
            adminRoutes(authDb, oauthDb, registry)
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
