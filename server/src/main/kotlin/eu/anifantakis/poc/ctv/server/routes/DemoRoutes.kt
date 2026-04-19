package eu.anifantakis.poc.ctv.server.routes

import eu.anifantakis.poc.ctv.server.scheduler.SchedulerDb
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class DemoUserDto(val username: String, val password: String)

/**
 * Tiny typed endpoint that proves server-to-MySQL connectivity. Uses the
 * server's own MySQL credentials (from config.properties) — the client
 * doesn't need any DB knowledge.
 */
fun Route.demoRoutes() {
    route("/api/demo") {
        get("/user") {
            try {
                val user = SchedulerDb.connection().use { c ->
                    c.prepareStatement("SELECT username, password FROM test.user LIMIT 1").use { ps ->
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) {
                                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No user rows"))
                                return@get
                            }
                            DemoUserDto(
                                username = rs.getString("username") ?: "",
                                password = rs.getString("password") ?: ""
                            )
                        }
                    }
                }
                call.respond(user)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "error" to (e.message ?: "Query failed"),
                        "type" to e.javaClass.simpleName
                    )
                )
            }
        }
    }
}
