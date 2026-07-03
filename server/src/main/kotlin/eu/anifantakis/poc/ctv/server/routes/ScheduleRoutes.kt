package eu.anifantakis.poc.ctv.server.routes

import eu.anifantakis.poc.ctv.server.auth.UserRole
import eu.anifantakis.poc.ctv.server.plugins.authUser
import eu.anifantakis.poc.ctv.server.scheduler.CommercialRow
import eu.anifantakis.poc.ctv.server.scheduler.SchedulerDb
import eu.anifantakis.poc.ctv.server.scheduler.breakZoneColorArgb
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

fun Route.scheduleRoutes(db: SchedulerDb) {
    route("/api") {
        get("/breaks") {
            // JDBC is blocking - keep it off Ktor's request threads
            val breaks = withContext(Dispatchers.IO) { db.loadBreaks() }.map {
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

            // JDBC is blocking - keep it off Ktor's request threads
            val (cells, commercialsByKey) = withContext(Dispatchers.IO) {
                db.ensureMonthSeeded(year, month)
                db.loadMonth(year, month)
            }

            // CUSTOMER_VIEWER data scoping: they only ever receive their own
            // commercials. Cell aggregates (spot count, duration) are
            // recomputed from the filtered list, and cells with none of their
            // spots are omitted entirely - the client renders the filtered
            // world as-is, so reports built from it are scoped too.
            val user = call.authUser()
            val onlyClientCode = user.clientCode?.takeIf { user.role == UserRole.CUSTOMER_VIEWER }

            val dtos = cells.mapNotNull { cell ->
                var coms = commercialsByKey[cell.breakId to cell.date].orEmpty()
                var spotCount = cell.spotCount
                var totalDuration = cell.totalDurationSeconds

                if (onlyClientCode != null) {
                    coms = coms.filter { it.clientCode == onlyClientCode }
                    if (coms.isEmpty()) return@mapNotNull null
                    spotCount = coms.size
                    totalDuration = coms.sumOf(CommercialRow::durationSeconds)
                }

                CellDto(
                    breakId = cell.breakId,
                    date = cell.date.toString(),
                    spotCount = spotCount,
                    totalDurationSeconds = totalDuration,
                    zoneColorArgb = cell.zoneColorArgb,
                    commercials = coms.map {
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
                )
            }

            call.respond(ScheduleDto(year = year, month = month, cells = dtos))
        }
    }
}
