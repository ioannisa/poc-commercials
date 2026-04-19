package eu.anifantakis.poc.ctv.server.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

// POC ONLY: this endpoint runs arbitrary SQL against arbitrary JDBC URLs.
// Never expose it outside localhost.

@Serializable
data class DbQueryRequest(
    val jdbcUrl: String = "jdbc:mysql://localhost:3306/test",
    val username: String = "root",
    val password: String = "",
    val sql: String = "SELECT * FROM user"
)

@Serializable
data class DbQueryResponse(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val rowCount: Int
)

fun Route.dbRoutes() {
    route("/api/db") {
        post("/query") {
            try {
                val req = call.receive<DbQueryRequest>()
                // Ensure the driver is registered (JDBC 4 auto-discovery usually handles this)
                Class.forName("com.mysql.cj.jdbc.Driver")

                DriverManager.getConnection(req.jdbcUrl, req.username, req.password).use { conn ->
                    conn.prepareStatement(req.sql).use { stmt ->
                        val rs: ResultSet = stmt.executeQuery()
                        val meta = rs.metaData
                        val colCount = meta.columnCount
                        val columns = (1..colCount).map { meta.getColumnLabel(it) }
                        val rows = mutableListOf<List<String?>>()
                        while (rs.next()) {
                            val row = (1..colCount).map { idx ->
                                when (meta.getColumnType(idx)) {
                                    Types.NULL -> null
                                    else -> rs.getObject(idx)?.toString()
                                }
                            }
                            rows += row
                        }
                        call.respond(DbQueryResponse(columns, rows, rows.size))
                    }
                }
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
