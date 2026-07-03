package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.plugins.requireRoleAnywhere
import eu.anifantakis.commercials.server.scheduler.CentralDb
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
 * round-tripping a row from the `test.user` demo table on the CENTRAL
 * server. Uses the server's own MySQL credentials — the client doesn't need
 * any DB knowledge. Failures are handled by the StatusPages plugin.
 */
fun Route.demoRoutes(db: CentralDb) {
    route("/api/demo") {
        get("/user") {
            // Exposes a raw DB row - admins only (NORMAL_USER on any station)
            if (!call.requireRoleAnywhere(UserRole.NORMAL_USER)) return@get

            val user = withContext(Dispatchers.IO) {
                db.connection().use { c ->
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
