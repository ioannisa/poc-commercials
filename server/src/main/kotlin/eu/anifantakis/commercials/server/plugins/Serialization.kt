package eu.anifantakis.commercials.server.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

/**
 * JSON content negotiation for the REST API - installed on the ROUTING ROOT,
 * not the Application. ContentNegotiation is route-scoped in Ktor 3, and Ktor
 * forbids mixing an application-level install with a route-level one; the MCP
 * streamable subtree needs its own `json(McpJson)` override (JSON-RPC requires
 * explicitNulls=false / encodeDefaults=true / no class discriminator - see
 * McpStreamable.kt). With both installs on routes, Ktor runs the NEAREST one:
 * every REST route keeps this Json, `/mcp/http` gets McpJson.
 */
fun Application.configureSerialization() {
    routing {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
    }
}
