package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.config.AppConfig
import eu.anifantakis.poc.ctv.reports.dto.ReportRequest
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * HTTP client for calling the report server API.
 * Used by browser-based platforms (JS and WASM) to generate PDFs via the
 * backend. Generic: any report id the server has a template for works here.
 */
object ReportApiClient {

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
        }
    }

    /**
     * Generate a PDF report via the server API.
     * @return PDF bytes on success, failure with the server error otherwise
     */
    suspend fun generatePdf(request: ReportRequest): Result<ByteArray> {
        return try {
            val response = httpClient.post("$serverBaseUrl/api/reports/${request.reportId}") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status.isSuccess()) {
                Result.success(response.readRawBytes())
            } else {
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
