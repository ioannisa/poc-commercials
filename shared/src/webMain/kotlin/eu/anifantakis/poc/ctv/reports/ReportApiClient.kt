package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.models.ProgramFlowReportData
import eu.anifantakis.poc.ctv.reports.models.ReportConfig
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HTTP client for calling the report server API.
 * Used by browser-based platforms (JS and WASM) to generate PDFs via the backend.
 */
object ReportApiClient {

    // Configure this to point to your report server
    var serverBaseUrl: String = "http://localhost:8080"

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
     * Generate a Program Flow PDF report via the server API
     * @return PDF bytes on success, null on failure
     */
    suspend fun generateProgramFlowPdf(
        reportData: ProgramFlowReportData,
        config: ReportConfig,
        fileName: String
    ): Result<ByteArray> {
        return try {
            val request = toServerRequest(reportData, config, fileName)

            val response = httpClient.post("$serverBaseUrl/api/reports/program-flow") {
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
     * Convert client-side report data to server API request format
     */
    private fun toServerRequest(
        reportData: ProgramFlowReportData,
        config: ReportConfig,
        fileName: String
    ): ProgramFlowServerRequest {
        // Group items by time slot
        val groupedItems = reportData.items.groupBy { it.timeSlot }

        val timeSlotGroups = groupedItems.map { (timeSlot, items) ->
            val lastItem = items.lastOrNull()
            TimeSlotGroupDto(
                timeLabel = timeSlot,
                items = items.map { item ->
                    ProgramFlowItemDto(
                        message = item.message,
                        time = item.timeSlot,
                        duration = item.duration,
                        program = item.program,
                        notes = item.notes
                    )
                },
                totalDuration = lastItem?.groupTotalDuration ?: "00:00",
                spotCount = lastItem?.groupSpotCount ?: items.size
            )
        }

        return ProgramFlowServerRequest(
            title = reportData.title,
            date = reportData.date.toString(), // ISO format
            emptyTimeIndicator = reportData.emptyTimeFormatted,
            timeSlotGroups = timeSlotGroups,
            fileName = fileName,
            logoPath = config.logoPath
        )
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

// DTOs matching the server API

@Serializable
data class ProgramFlowServerRequest(
    val title: String,
    val date: String,
    val emptyTimeIndicator: String,
    val timeSlotGroups: List<TimeSlotGroupDto>,
    val fileName: String? = null,
    val logoPath: String? = null
)

@Serializable
data class TimeSlotGroupDto(
    val timeLabel: String,
    val items: List<ProgramFlowItemDto>,
    val totalDuration: String,
    val spotCount: Int
)

@Serializable
data class ProgramFlowItemDto(
    val message: String,
    val time: String,
    val duration: String,
    val program: String,
    val notes: String
)
