package eu.anifantakis.poc.ctv.data

import androidx.compose.ui.graphics.Color
import eu.anifantakis.poc.ctv.db.dbServerBaseUrl
import eu.anifantakis.poc.ctv.grids.BreakSlot
import eu.anifantakis.poc.ctv.grids.BreakZone
import eu.anifantakis.poc.ctv.grids.CommercialItem
import eu.anifantakis.poc.ctv.grids.SchedulerCellData
import eu.anifantakis.poc.ctv.grids.SchedulerKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class BreakSlotDto(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String,
    val zone: String,
    val zoneColorArgb: Int
)

@Serializable
private data class CommercialDto(
    val id: Long,
    val position: Int,
    val clientCode: String,
    val clientName: String,
    val message: String,
    val durationSeconds: Int,
    val type: String,
    val contract: String,
    val flow: String
)

@Serializable
private data class CellDto(
    val breakId: Long,
    val date: String,
    val spotCount: Int,
    val totalDurationSeconds: Int,
    val zoneColorArgb: Int,
    val commercials: List<CommercialDto>
)

@Serializable
private data class ScheduleDto(
    val year: Int,
    val month: Int,
    val cells: List<CellDto>
)

private val client by lazy {
    HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
}

object ScheduleRepository {

    suspend fun getBreaks(): List<BreakSlot> {
        val dtos: List<BreakSlotDto> = client.get("${dbServerBaseUrl()}/api/breaks").body()
        return dtos.map {
            BreakSlot(
                id = it.id,
                time = LocalTime(it.hour, it.minute),
                label = it.label,
                zone = BreakZone.valueOf(it.zone),
                zoneColor = Color(it.zoneColorArgb.toLong() and 0xFFFFFFFFL)
            )
        }
    }

    suspend fun getSchedule(year: Int, month: Int): Map<SchedulerKey, SchedulerCellData> {
        val dto: ScheduleDto = client.get("${dbServerBaseUrl()}/api/schedule") {
            parameter("year", year)
            parameter("month", month)
        }.body()

        return dto.cells.associate { cell ->
            val date = LocalDate.parse(cell.date)
            val key = SchedulerKey(cell.breakId, date)
            val commercials = cell.commercials
                .sortedBy { it.position }
                .map {
                    CommercialItem(
                        id = it.id,
                        clientCode = it.clientCode,
                        clientName = it.clientName,
                        message = it.message,
                        durationSeconds = it.durationSeconds,
                        type = it.type,
                        contract = it.contract,
                        flow = it.flow
                    )
                }
                .toImmutableList()
            key to SchedulerCellData(
                spotCount = cell.spotCount,
                totalDurationSeconds = cell.totalDurationSeconds,
                zoneColor = Color(cell.zoneColorArgb.toLong() and 0xFFFFFFFFL),
                commercials = commercials
            )
        }
    }
}
