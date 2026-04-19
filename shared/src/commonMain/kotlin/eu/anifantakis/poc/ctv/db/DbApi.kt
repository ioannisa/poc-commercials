package eu.anifantakis.poc.ctv.db

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class DbUser(val username: String, val password: String)

@Serializable
private data class DbQueryRequest(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val sql: String
)

@Serializable
private data class DbQueryResponse(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val rowCount: Int
)

private val httpClient by lazy {
    HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
}

// Base URL varies by platform: android emulator needs 10.0.2.2 for host loopback.
expect fun dbServerBaseUrl(): String

suspend fun fetchDbUser(): DbUser {
    val response: DbQueryResponse = httpClient.post("${dbServerBaseUrl()}/api/db/query") {
        contentType(ContentType.Application.Json)
        setBody(
            DbQueryRequest(
                jdbcUrl = "jdbc:mysql://localhost:3306/test",
                username = "root",
                password = "rootpass123",
                sql = "SELECT * FROM user LIMIT 1"
            )
        )
    }.body()

    val row = response.rows.firstOrNull() ?: error("No rows returned")
    val usernameIdx = response.columns.indexOf("username")
    val passwordIdx = response.columns.indexOf("password")
    require(usernameIdx >= 0 && passwordIdx >= 0) {
        "Missing columns in response: ${response.columns}"
    }
    return DbUser(
        username = row.getOrNull(usernameIdx) ?: error("username is null"),
        password = row.getOrNull(passwordIdx) ?: error("password is null")
    )
}
