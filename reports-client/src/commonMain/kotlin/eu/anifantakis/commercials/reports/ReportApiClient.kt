package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.reports.dto.ReportBatchRequest
import eu.anifantakis.commercials.reports.dto.ReportRequest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Server-side report rendering, for EVERY non-JVM platform (browsers and
 * mobile alike). Rides the app's shared [ApiHttpClient] - bearer token,
 * station stamping and the 401 -> session-clear policy come from the same
 * place as every other API call, instead of the hand-rolled duplicate HTTP
 * stack this class used to carry in webMain.
 */
class ReportApiClient(private val api: ApiHttpClient) {

    /** Generate a PDF report via the server. PDF bytes on success. */
    suspend fun generatePdf(request: ReportRequest): Result<ByteArray> =
        postForPdf("/api/reports/${request.reportId}", request)

    /** Generate a batch of reports as one PDF via the server. */
    suspend fun generatePdf(batch: ReportBatchRequest): Result<ByteArray> =
        postForPdf("/api/reports/batch", batch)

    private suspend inline fun <reified T> postForPdf(path: String, body: T): Result<ByteArray> =
        runCatching {
            // expectSuccess + the shared HttpResponseValidator handle non-2xx
            // (including 401 -> clear session -> back to Login).
            api.client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.readRawBytes()
        }

    /** Whether the report server answers at all. */
    suspend fun checkServerStatus(): Boolean =
        runCatching { api.client.get("/api/reports/status") }.isSuccess
}
