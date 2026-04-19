package eu.anifantakis.poc.ctv.server.routes

import eu.anifantakis.poc.ctv.server.scheduler.SchedulerDb
import eu.anifantakis.poc.ctv.server.scheduler.breakZoneColorArgb
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class BreakSlotDto(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String,
    val zone: String,
    val zoneColorArgb: Int
)

@Serializable
data class CommercialDto(
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
data class CellDto(
    val breakId: Long,
    val date: String, // ISO yyyy-MM-dd
    val spotCount: Int,
    val totalDurationSeconds: Int,
    val zoneColorArgb: Int,
    val commercials: List<CommercialDto>
)

@Serializable
data class ScheduleDto(
    val year: Int,
    val month: Int,
    val cells: List<CellDto>
)

fun Route.scheduleRoutes() {
    route("/api") {
        get("/breaks") {
            val breaks = SchedulerDb.loadBreaks().map {
                BreakSlotDto(
                    id = it.id,
                    hour = it.hour,
                    minute = it.minute,
                    label = it.label,
                    zone = it.zone.name,
                    zoneColorArgb = breakZoneColorArgb(it.zone)
                )
            }
            call.respond(breaks)
        }

        get("/schedule") {
            val year = call.request.queryParameters["year"]?.toIntOrNull()
            val month = call.request.queryParameters["month"]?.toIntOrNull()
            if (year == null || month == null || month !in 1..12) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "year and month (1..12) required"))
                return@get
            }

            SchedulerDb.ensureMonthSeeded(year, month)
            val (cells, commercialsByKey) = SchedulerDb.loadMonth(year, month)

            val dtos = cells.map { cell ->
                val coms = commercialsByKey[cell.breakId to cell.date].orEmpty().map {
                    CommercialDto(
                        id = it.id,
                        position = it.position,
                        clientCode = it.clientCode,
                        clientName = it.clientName,
                        message = it.message,
                        durationSeconds = it.durationSeconds,
                        type = it.type,
                        contract = it.contract,
                        flow = it.flow
                    )
                }
                CellDto(
                    breakId = cell.breakId,
                    date = cell.date.toString(),
                    spotCount = cell.spotCount,
                    totalDurationSeconds = cell.totalDurationSeconds,
                    zoneColorArgb = cell.zoneColorArgb,
                    commercials = coms
                )
            }

            call.respond(ScheduleDto(year = year, month = month, cells = dtos))
        }
    }
}
