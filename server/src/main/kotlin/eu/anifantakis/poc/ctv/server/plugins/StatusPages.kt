package eu.anifantakis.poc.ctv.server.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*

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
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "Malformed request"))
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "Invalid request"))
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error(
                "Unhandled error for ${call.request.httpMethod.value} ${call.request.uri}",
                cause
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Internal server error"))
            )
        }
    }
}
