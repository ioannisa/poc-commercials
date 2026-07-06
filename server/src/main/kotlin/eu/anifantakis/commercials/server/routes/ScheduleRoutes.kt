package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.plugins.StationAccess
import eu.anifantakis.commercials.server.plugins.stationAccessOrRespond
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.scheduler.breakZoneColorArgb
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.LocalDate

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
    val excludeFromReports: Boolean = false,
    val flow: String
)

@Serializable
data class CellDto(
    val breakId: Long,
    val date: String, // ISO yyyy-MM-dd
    val spotCount: Int,
    val totalDurationSeconds: Int,
    val zoneColorArgb: Int,
    /** The programme airing at this slot (first placement's), when it has one. */
    val programName: String? = null,
    val commercials: List<CommercialDto>
)

@Serializable
data class ScheduleDto(
    val year: Int,
    val month: Int,
    val cells: List<CellDto>
)

@Serializable
data class ContractLineDto(
    val lineId: Long,
    val contractNumber: String,
    val isGift: Boolean,
    val lineNo: Int,
    val desiredQty: Int,
    val spotCount: Int,
    val placements: Int,
    val totalSeconds: Long,
    val entryDate: String? = null,
)

@Serializable
data class FinderSpotDto(
    val spotId: Long,
    val description: String,
    val durationSeconds: Int,
    val placements: Int,
    val totalSeconds: Long = 0,
)

@Serializable
data class AddPlacementRequest(
    val spotId: Long,
    val breakId: Long,
    /** ISO yyyy-MM-dd */
    val date: String,
)

@Serializable
data class ReorderPlacementsRequest(
    val breakId: Long,
    /** ISO yyyy-MM-dd */
    val date: String,
    /** The cell's placement ids in the new display order. */
    val orderedIds: List<Long>,
)

/**
 * Schedule data, station-scoped: every request carries `?station=<id>`; the
 * caller must hold a grant on that station, and the grant's role drives
 * filtering (customers only ever receive their own commercials).
 */
fun Route.scheduleRoutes(registry: StationRegistry) {
    route("/api") {
        get("/breaks") {
            val access = call.stationAccessOrRespond(registry) ?: return@get

            // JDBC is blocking - keep it off Ktor's request threads
            val breaks = withContext(Dispatchers.IO) { access.db.loadBreaks() }.map {
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

            val access = call.stationAccessOrRespond(registry) ?: return@get

            // JDBC is blocking - keep it off Ktor's request threads
            val (cells, commercialsByKey) = withContext(Dispatchers.IO) {
                access.db.ensureMonthSeeded(year, month)
                access.db.loadMonth(year, month)
            }

            // CUSTOMER_VIEWER data scoping: they only ever receive their own
            // commercials on this station. Cell aggregates (spot count,
            // duration) are recomputed from the filtered list, and cells with
            // none of their spots are omitted entirely - the client renders
            // the filtered world as-is, so reports built from it are scoped too.
            val grant = access.grant
            val onlyClientCode = grant.clientCode?.takeIf { grant.role == UserRole.CUSTOMER_VIEWER }

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
                    programName = cell.programName,
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
                            excludeFromReports = it.excludeFromReports,
                            flow = it.flow
                        )
                    }
                )
            }

            call.respond(ScheduleDto(year = year, month = month, cells = dtos))
        }

        // ── spot finder (the legacy "Εύρεση" Details Console) ───────────
        // Editing tools: NORMAL_USER only, like the email routes.

        // The party's contract lines ("products" - ERP identity pending,
        // presented by contract number + line no with computed stats)
        get("/finder/contracts") {
            val access = call.editorAccessOrRespond(registry) ?: return@get
            val byTrader = when (call.request.queryParameters["kind"] ?: "customer") {
                "customer" -> false
                "trader" -> true
                else -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "kind must be 'customer' or 'trader'"))
                    return@get
                }
            }
            val clientCode = call.request.queryParameters["clientCode"]
            if (clientCode.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "clientCode required"))
                return@get
            }
            val lines = withContext(Dispatchers.IO) {
                access.db.partyContractLines(clientCode, byTrader).map {
                    ContractLineDto(
                        it.lineId, it.contractNumber, it.isGift, it.lineNo,
                        it.desiredQty, it.spotCount, it.placements, it.totalSeconds,
                        it.entryDate,
                    )
                }
            }
            call.respond(lines)
        }

        // The spots (creatives) of one contract line
        get("/finder/spots") {
            val access = call.editorAccessOrRespond(registry) ?: return@get
            val lineId = call.request.queryParameters["lineId"]?.toLongOrNull()
            if (lineId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "numeric lineId required"))
                return@get
            }
            val spots = withContext(Dispatchers.IO) {
                access.db.contractLineSpots(lineId).map {
                    FinderSpotDto(it.spotId, it.description, it.durationSeconds, it.placements, it.totalSeconds)
                }
            }
            call.respond(spots)
        }

        // ── placement editing (the grid's 'a'/'r' keys) ─────────────────

        // Appends the spot at the end of the (break, date) cell; responds
        // with the new placement in the same shape the month grid serves -
        // CommercialDto.id IS the placement id the client passes to DELETE.
        post("/schedule/placements") {
            val access = call.editorAccessOrRespond(registry) ?: return@post
            val req = call.receive<AddPlacementRequest>()
            val date = runCatching { LocalDate.parse(req.date) }.getOrNull()
            if (date == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "date must be yyyy-MM-dd"))
                return@post
            }
            val row = withContext(Dispatchers.IO) { access.db.addPlacement(req.spotId, req.breakId, date) }
            if (row == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown spot ${req.spotId} on this station"))
                return@post
            }
            call.respond(
                CommercialDto(
                    id = row.id,
                    position = row.position,
                    clientCode = row.clientCode,
                    clientName = row.clientName,
                    message = row.message,
                    durationSeconds = row.durationSeconds,
                    type = row.type,
                    contract = row.contract,
                    excludeFromReports = row.excludeFromReports,
                    flow = row.flow,
                )
            )
        }

        // Persists the ordering the operator arranged in the break detail
        // screen - list indexes become positions.
        put("/schedule/placements/order") {
            val access = call.editorAccessOrRespond(registry) ?: return@put
            val req = call.receive<ReorderPlacementsRequest>()
            val date = runCatching { LocalDate.parse(req.date) }.getOrNull()
            if (date == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "date must be yyyy-MM-dd"))
                return@put
            }
            val ok = withContext(Dispatchers.IO) {
                access.db.reorderPlacements(req.breakId, date, req.orderedIds)
            }
            if (ok) call.respond(HttpStatusCode.NoContent)
            else call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to "orderedIds do not match the cell's current placements - reload the month")
            )
        }

        delete("/schedule/placements/{id}") {
            val access = call.editorAccessOrRespond(registry) ?: return@delete
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "numeric id required"))
                return@delete
            }
            val deleted = withContext(Dispatchers.IO) { access.db.deletePlacement(id) }
            if (deleted) call.respond(HttpStatusCode.NoContent)
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "No placement $id"))
        }
    }
}

/** Editing the schedule is staff work - NORMAL_USER on the station required. */
private suspend fun io.ktor.server.application.ApplicationCall.editorAccessOrRespond(
    registry: StationRegistry,
): StationAccess? {
    val access = stationAccessOrRespond(registry) ?: return null
    if (access.grant.role != UserRole.NORMAL_USER) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "Requires full access on this station"))
        return null
    }
    return access
}
