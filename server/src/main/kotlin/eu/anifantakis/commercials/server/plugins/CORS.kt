package eu.anifantakis.commercials.server.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureCORS() {
    install(CORS) {
        // Allow requests from any origin (for development)
        anyHost()

        // Allow common HTTP methods
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)

        // Allow common headers
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.Accept)

        // MCP session + protocol-negotiation headers (browser-based MCP clients)
        allowHeader("Mcp-Session-Id")
        allowHeader("Mcp-Protocol-Version")

        // NOTE: allowCredentials is deliberately NOT set. Nothing in the app
        // uses cookies or HTTP auth, and anyHost() + credentials would make
        // Ktor reflect arbitrary Origins on credentialed requests - the
        // combination browsers forbid for `*` exists precisely because it
        // lets any website script authenticated calls against this API.

        // Expose headers to the client
        exposeHeader(HttpHeaders.ContentDisposition)
        exposeHeader("Mcp-Session-Id")
        exposeHeader("Mcp-Protocol-Version")
    }
}
