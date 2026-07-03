package eu.anifantakis.poc.ctv.server.routes

import eu.anifantakis.poc.ctv.server.auth.UserRole
import eu.anifantakis.poc.ctv.server.plugins.requireRole
import eu.anifantakis.poc.ctv.server.scheduler.SchedulerDb
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class DemoUserDto(val username: String, val password: String)

/**
 * Tiny typed endpoint that proves server-to-MySQL connectivity by
 * round-tripping a row from the `test.user` demo table. Uses the server's
 * own MySQL credentials (from config.properties) — the client doesn't need
 * any DB knowledge. Failures are handled by the StatusPages plugin.
 */
fun Route.demoRoutes() {
    route("/api/demo") {
        get("/user") {
            // Exposes a raw DB row - admin-only
            if (!call.requireRole(UserRole.NORMAL_USER)) return@get

            val user = withContext(Dispatchers.IO) {
                SchedulerDb.connection().use { c ->
                    c.prepareStatement("SELECT username, password FROM test.user LIMIT 1").use { ps ->
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) null
                            else DemoUserDto(
                                username = rs.getString("username") ?: "",
                                password = rs.getString("password") ?: ""
                            )
                        }
                    }
                }
            }

            if (user == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No user rows"))
            } else {
                call.respond(user)
            }
        }
    }
}
