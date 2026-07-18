package eu.anifantakis.commercials.server.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.JsonPrimitive

/**
 * Global exception -> HTTP status mapping, so every route gets consistent
 * JSON error bodies without per-route try/catch:
 *
 * - [BadRequestException]: Ktor's receive() wraps any body-deserialization
 *   failure in this ("Failed to convert request body to ..."). Without this
 *   mapping a malformed request surfaced as a 500.
 * - [IllegalArgumentException]: validation failures (e.g. the report engine's
 *   unknown parameter/field checks) are the caller's fault -> 400.
 * - Everything else is a real server-side failure -> 500, logged with the
 *   request line since the response body alone won't tell us where it happened.
 *
 * The bodies are built by hand and sent with [ApplicationCall.respondText],
 * NOT `respond(mapOf(...))`: ContentNegotiation is installed on the ROUTING
 * root (it must be - the MCP subtree overrides it, see Serialization.kt), but
 * StatusPages handlers run OUTSIDE the routing pipeline, where no converter
 * is registered. A `respond(map)` here therefore came back as a bare 406
 * Not Acceptable with an empty body - the client saw "406" for what was
 * actually a provider socket timeout.
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respondErrorJson(HttpStatusCode.BadRequest, cause.message ?: "Malformed request")
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respondErrorJson(HttpStatusCode.BadRequest, cause.message ?: "Invalid request")
        }
        exception<Throwable> { call, cause ->
            call.application.log.error(
                "Unhandled error for ${call.request.httpMethod.value} ${call.request.uri}",
                cause
            )
            call.respondErrorJson(HttpStatusCode.InternalServerError, cause.message ?: "Internal server error")
        }
    }
}

/** `{"error": "..."}` with correct JSON escaping, independent of ContentNegotiation. */
private suspend fun ApplicationCall.respondErrorJson(status: HttpStatusCode, message: String) {
    respondText(
        text = """{"error":${JsonPrimitive(message)}}""",
        contentType = ContentType.Application.Json,
        status = status,
    )
}
