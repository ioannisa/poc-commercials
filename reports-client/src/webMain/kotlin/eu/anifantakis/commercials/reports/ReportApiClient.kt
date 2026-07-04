package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.reports.dto.ReportBatchRequest
import eu.anifantakis.commercials.reports.dto.ReportRequest
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * HTTP client for calling the report server API (Koin singleton).
 * Used by browser-based platforms (JS and WASM) to generate PDFs via the
 * backend. Generic: any report id the server has a template for works here.
 */
class ReportApiClient(private val session: AuthSession) {

    // Same config source as every other network call in the app
    // (config.properties -> AppConfig, loaded at application startup)
    private val serverBaseUrl: String
        get() = AppConfig.require().serverBaseUrl

    private val httpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            // Runs per request - picks up the current session token
            defaultRequest {
                session.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
        }
    }

    /**
     * Generate a PDF report via the server API.
     * @return PDF bytes on success, failure with the server error otherwise
     */
    suspend fun generatePdf(request: ReportRequest): Result<ByteArray> =
        postForPdf("$serverBaseUrl/api/reports/${request.reportId}", request)

    /**
     * Generate a batch of reports as one PDF via the server API.
     */
    suspend fun generatePdf(batch: ReportBatchRequest): Result<ByteArray> =
        postForPdf("$serverBaseUrl/api/reports/batch", batch)

    private suspend inline fun <reified T> postForPdf(url: String, body: T): Result<ByteArray> {
        return try {
            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            if (response.status.isSuccess()) {
                Result.success(response.readRawBytes())
            } else {
                // A rejected token here means the session is dead - clear it so
                // the app returns to Login (same 401 policy as the JSON client).
                if (response.status == HttpStatusCode.Unauthorized) session.clear()
                val errorBody = response.bodyAsText()
                Result.failure(Exception("Server error ${response.status}: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if the report server is available
     */
    suspend fun checkServerStatus(): Boolean {
        return try {
            val response = httpClient.get("$serverBaseUrl/api/reports/status")
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }
}
