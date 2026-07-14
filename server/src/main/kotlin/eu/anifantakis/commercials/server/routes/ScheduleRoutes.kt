package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.plugins.StationAccess
import eu.anifantakis.commercials.server.plugins.stationAccessOrRespond
import eu.anifantakis.commercials.server.scheduler.GridViewMode
import eu.anifantakis.commercials.server.scheduler.breakZoneColorArgb
import eu.anifantakis.commercials.server.scheduler.formatHhMm
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * A ROW of the grid. Its identity is its TIME - there is no break id, because
 * there is no break table: a break is a time something aired at (see GroupDb).
 *
 * A row may be EMPTY (the hourly/half-hourly scaffold prints 08:00 whether or
 * not anything airs there), which is exactly why the id had to go: an empty row
 * has no break to have one.
 */
@Serializable
data class BreakSlotDto(
    /** "HH:mm" - the row's identity, and the break's. */
    val time: String,
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
    /** Sales item of the contract line (Break Console Τύπος); null -> show [type]. */
    val salesItem: String? = null,
    /** Contract NUMBER (gifts included - the gift marker lives in the item name). */
    val contract: String,
    val isGift: Boolean = false,
    val excludeFromReports: Boolean = false,
    val flow: String
)

/**
 * One box of the grid. Aggregates ONLY - no airings.
 *
 * The box shows a spot count and a duration; it never read the airings it used to
 * be shipped with (13,009 of them, 7.79 MB, for 1,295 boxes). They come from
 * [CellCommercialsDto] now, on demand.
 */
@Serializable
data class CellDto(
    /** "HH:mm" - the break this cell belongs to. */
    val time: String,
    val date: String, // ISO yyyy-MM-dd
    val spotCount: Int,
    val totalDurationSeconds: Int,
    val zoneColorArgb: Int,
    /** The programme airing at this slot (first placement's), when it has one. */
    val programName: String? = null,
)

/**
 * The whole grid in ONE response: its ROWS and its CELLS.
 *
 * They used to be two endpoints the client fetched back-to-back (`/breaks` then
 * `/schedule`), which cost two round trips AND two scans of the same month - the
 * rows are just the DISTINCT times of the cells. Now one scan answers both.
 */
@Serializable
data class ScheduleDto(
    val year: Int,
    val month: Int,
    val rows: List<BreakSlotDto>,
    val cells: List<CellDto>
)

/** The airings of ONE cell - fetched only when a break is opened or printed. */
@Serializable
data class CellCommercialsDto(
    val time: String,
    val date: String,
    val commercials: List<CommercialDto>,
)

@Serializable
data class CommercialsDto(
    val year: Int,
    val month: Int,
    val cells: List<CellCommercialsDto>,
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
    /**
     * "HH:mm". It need not be a time anything has aired at: putting a spot at
     * 23:55 is how the 23:55 break comes into being (the legacy console's
     * "Πρόσθεση νέου διαλείμματος - Ώρα:" box did just this).
     */
    val time: String,
    /** ISO yyyy-MM-dd */
    val date: String,
)

@Serializable
data class ReorderPlacementsRequest(
    /** "HH:mm" - the break whose spots are being reordered. */
    val time: String,
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
        /**
         * The grid's ROWS for a month, in the requested view.
         *
         * It takes a period now, and a view, because a break is not standing
         * configuration to be listed - it is a time something aired at, so
         * "this station's breaks" is only a question with a month attached.
         * The scaffold (the empty 08:00 row an hourly view still prints) is
         * resolved here rather than on the client: its rule, the zone colours,
         * and the station's `emptyRowsFrom` all live server-side, and splitting
         * them would put the same `when(hour)` in two codebases again.
         */
        get("/breaks") {
            val year = call.request.queryParameters["year"]?.toIntOrNull()
            val month = call.request.queryParameters["month"]?.toIntOrNull()
            if (year == null || month == null || month !in 1..12) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "year and month (1..12) required"))
                return@get
            }
            val modeParam = call.request.queryParameters["mode"] ?: GridViewMode.CONDENSED.name
            val mode = runCatching { GridViewMode.valueOf(modeParam) }.getOrNull()
            if (mode == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "mode must be one of ${GridViewMode.entries.joinToString { it.name }}")
                )
                return@get
            }
            val access = call.stationAccessOrRespond(registry) ?: return@get

            // JDBC is blocking - keep it off Ktor's request threads
            val breaks = withContext(Dispatchers.IO) {
                access.db.ensureMonthSeeded(year, month)
                access.db.gridRows(year, month, mode)
            }.map {
                BreakSlotDto(
                    time = it.label,
                    zone = it.zone.name,
                    zoneColorArgb = breakZoneColorArgb(it.zone)
                )
            }
            call.respond(breaks)
        }

        /**
         * THE MONTH GRID. Aggregates only - a spot count and a total duration per
         * cell, which is exactly what a little box draws.
         *
         * It does NOT carry the airings. Serving them here meant 13,009 rows and
         * 7.79 MB of JSON to paint 1,295 boxes on the busiest month; the boxes
         * never read a single one. Whoever actually wants an airing asks for it:
         * `/schedule/commercials` below.
         */
        get("/schedule") {
            val year = call.request.queryParameters["year"]?.toIntOrNull()
            val month = call.request.queryParameters["month"]?.toIntOrNull()
            if (year == null || month == null || month !in 1..12) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "year and month (1..12) required"))
                return@get
            }
            val modeParam = call.request.queryParameters["mode"] ?: GridViewMode.CONDENSED.name
            val mode = runCatching { GridViewMode.valueOf(modeParam) }.getOrNull()
            if (mode == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "mode must be one of ${GridViewMode.entries.joinToString { it.name }}")
                )
                return@get
            }

            val access = call.stationAccessOrRespond(registry) ?: return@get

            // CUSTOMER_VIEWER scoping goes INTO the aggregate: with no airings
            // coming back there is nothing left to filter in Kotlin, and a
            // customer must never be shown everybody's counts.
            val grant = access.grant
            val onlyClientCode = grant.clientCode?.takeIf { grant.role == UserRole.CUSTOMER_VIEWER }

            // ONE scan of the month. The rows are derived from the cells' own times
            // (see gridRowsFrom) - fetching them separately meant scanning the same
            // month twice and making the client wait for two round trips.
            //
            // JDBC is blocking - keep it off Ktor's request threads.
            val (cells, rows) = withContext(Dispatchers.IO) {
                access.db.ensureMonthSeeded(year, month)
                val c = access.db.loadMonthCells(year, month, onlyClientCode)
                c to access.db.gridRowsFrom(c.map { it.time }.distinct(), mode)
            }

            call.respond(
                ScheduleDto(
                    year = year,
                    month = month,
                    rows = rows.map {
                        BreakSlotDto(
                            time = it.label,
                            zone = it.zone.name,
                            zoneColorArgb = breakZoneColorArgb(it.zone),
                        )
                    },
                    cells = cells.map { cell ->
                        CellDto(
                            time = formatHhMm(cell.time),
                            date = cell.date.toString(),
                            spotCount = cell.spotCount,
                            totalDurationSeconds = cell.totalDurationSeconds,
                            zoneColorArgb = cell.zoneColorArgb,
                            programName = cell.programName,
                        )
                    },
                )
            )
        }

        /**
         * THE AIRINGS, for a slice of the month - and only when something needs
         * them: opening a break (`date` + `time`), printing a day (`date`),
         * printing one break across the month (`time`), printing the whole month
         * (neither).
         *
         * Same customer scoping as the grid.
         */
        get("/schedule/commercials") {
            val year = call.request.queryParameters["year"]?.toIntOrNull()
            val month = call.request.queryParameters["month"]?.toIntOrNull()
            if (year == null || month == null || month !in 1..12) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "year and month (1..12) required"))
                return@get
            }
            val dateParam = call.request.queryParameters["date"]
            val date = if (dateParam == null) null else runCatching { LocalDate.parse(dateParam) }.getOrNull()
            if (dateParam != null && date == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "date must be yyyy-MM-dd"))
                return@get
            }
            val timeParam = call.request.queryParameters["time"]
            val time = if (timeParam == null) null else parseHhMmOrNull(timeParam)
            if (timeParam != null && time == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "time must be HH:mm"))
                return@get
            }

            val access = call.stationAccessOrRespond(registry) ?: return@get
            val grant = access.grant
            val onlyClientCode = grant.clientCode?.takeIf { grant.role == UserRole.CUSTOMER_VIEWER }

            val byKey = withContext(Dispatchers.IO) {
                access.db.ensureMonthSeeded(year, month)
                access.db.loadCommercials(year, month, date, time)
            }

            val cells = byKey.mapNotNull { (key, rows) ->
                val coms = if (onlyClientCode == null) rows else rows.filter { it.clientCode == onlyClientCode }
                if (coms.isEmpty()) return@mapNotNull null
                CellCommercialsDto(
                    time = formatHhMm(key.first),
                    date = key.second.toString(),
                    commercials = coms.map {
                        CommercialDto(
                            id = it.id,
                            position = it.position,
                            clientCode = it.clientCode,
                            clientName = it.clientName,
                            message = it.message,
                            durationSeconds = it.durationSeconds,
                            type = it.type,
                            salesItem = it.salesItem,
                            contract = it.contract,
                            isGift = it.isGift,
                            excludeFromReports = it.excludeFromReports,
                            flow = it.flow
                        )
                    }
                )
            }
            call.respond(CommercialsDto(year = year, month = month, cells = cells))
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

        // Appends the spot at the end of the (time, date) cell; responds
        // with the new placement in the same shape the month grid serves -
        // CommercialDto.id IS the placement id the client passes to DELETE.
        //
        // The time need not already have a break: airing a spot there IS what
        // creates one, so this is also the "add a new break" endpoint.
        post("/schedule/placements") {
            val access = call.editorAccessOrRespond(registry) ?: return@post
            val req = call.receive<AddPlacementRequest>()
            val date = runCatching { LocalDate.parse(req.date) }.getOrNull()
            if (date == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "date must be yyyy-MM-dd"))
                return@post
            }
            val time = parseHhMmOrNull(req.time)
            if (time == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "time must be HH:mm"))
                return@post
            }
            val row = withContext(Dispatchers.IO) { access.db.addPlacement(req.spotId, time, date) }
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
            val time = parseHhMmOrNull(req.time)
            if (time == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "time must be HH:mm"))
                return@put
            }
            val ok = withContext(Dispatchers.IO) {
                access.db.reorderPlacements(time, date, req.orderedIds)
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

/**
 * "HH:mm" -> the break's time, or null if malformed. Seconds are rejected rather
 * than truncated: the client addresses a break by the exact label it was given,
 * and a 12:20:30 that silently became 12:20 would move the operator's spot.
 */
private fun parseHhMmOrNull(value: String): java.time.LocalTime? {
    val m = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(value.trim()) ?: return null
    val (h, min) = m.destructured
    return runCatching { java.time.LocalTime.of(h.toInt(), min.toInt()) }.getOrNull()
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
