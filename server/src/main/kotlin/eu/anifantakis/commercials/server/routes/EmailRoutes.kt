package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.mailer.EmailCell
import eu.anifantakis.commercials.mailer.EmailGridRow
import eu.anifantakis.commercials.mailer.ProgramTotal
import eu.anifantakis.commercials.mailer.ScheduleEmailData
import eu.anifantakis.commercials.mailer.SmtpMailer
import eu.anifantakis.commercials.mailer.SmtpSettings
import eu.anifantakis.commercials.mailer.SpotSection
import eu.anifantakis.commercials.mailer.renderScheduleEmail
import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.plugins.StationAccess
import eu.anifantakis.commercials.server.plugins.authUser
import eu.anifantakis.commercials.server.plugins.stationAccessOrRespond
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.scheduler.StationDb
import eu.anifantakis.commercials.server.stations.SmtpConfig
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

@Serializable
data class EmailCustomerDto(
    val code: String,
    val name: String,
    val email: String?,
    val spotCount: Int,
    val placementCount: Int,
)

@Serializable
data class EmailSpotDto(val spotId: Long, val description: String, val placements: Int)

@Serializable
data class EmailActivityDto(val year: Int, val month: Int, val placements: Int)

@Serializable
data class EmailLogDto(
    val id: Long,
    val customerCode: String,
    val customerName: String,
    val recipient: String,
    val subject: String,
    val year: Int,
    val month: Int,
    val spotCount: Int,
    val transmissionCount: Int,
    val sentBy: String,
    val sentAt: String,
    val status: String,
    val error: String? = null,
)

@Serializable
data class SendScheduleEmailRequest(
    val year: Int,
    val month: Int,
    val clientCode: String,
    /** "customer" (the spot's owner) or "trader" (the contract's payer). */
    val kind: String = "customer",
    /** Restrict to these spots; null/empty = all of the customer's spots. */
    val spotIds: List<Long> = emptyList(),
    /** Defaults to the customer's stored address. */
    val to: String? = null,
    /** Defaults to the legacy-style subject. */
    val subject: String? = null,
    /** The operator's free-text note above the sections (legacy tradition). */
    val personalMessage: String? = null,
)

/**
 * Customer schedule emails - the modern successor of the legacy app's
 * "ΠΡΟΓΡΑΜΜΑΤΙΣΜΟΙ" workflow (1,282 archived sends, 2006-2026): ONE email
 * per customer, with ONE SECTION PER SPOT so it is unambiguous which
 * creative aired when.
 *
 * - GET  /customers  substring search (`q`, min 3 chars) over all parties
 *                    with airings; `kind=customer` searches the spots'
 *                    owners (legacy cusID), `kind=trader` the contracts'
 *                    payers (legacy traid - agencies in "triangular" deals)
 * - GET  /activity   a party's active months (year/month/count, newest
 *                    first) - the year -> month drill-down after the search
 * - GET  /spots      a party's spots (creatives) in a month
 * - GET  /preview    renders the HTML without sending
 * - POST /send       renders and sends via the station's SMTP settings
 *
 * Staff-only: requires NORMAL_USER on the station (the super admin has that
 * implicitly everywhere).
 */
fun Route.emailRoutes(registry: StationRegistry) {
    route("/api/email/schedule") {

        // With thousands of customers a full list is useless - the client
        // sends `q` (min 3 chars, debounced) and gets the matching parties
        // across ALL time, busiest first; the month is picked afterwards
        // from /activity.
        get("/customers") {
            val access = call.staffAccessOrRespond(registry) ?: return@get
            val byTrader = call.byTraderOrRespond() ?: return@get
            val q = call.request.queryParameters["q"]?.trim().orEmpty()
            if (q.length < 3) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "q with at least 3 characters required"))
                return@get
            }
            val customers = withContext(Dispatchers.IO) {
                access.db.searchParties(q, byTrader).map {
                    EmailCustomerDto(
                        code = it.code,
                        name = it.name,
                        email = it.email,
                        spotCount = it.spotCount,
                        placementCount = it.placementCount,
                    )
                }
            }
            call.respond(customers)
        }

        // The party's active months, newest first - drives the year/month
        // drill-down under the selected party.
        get("/activity") {
            val access = call.staffAccessOrRespond(registry) ?: return@get
            val byTrader = call.byTraderOrRespond() ?: return@get
            val clientCode = call.request.queryParameters["clientCode"]
            if (clientCode.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "clientCode required"))
                return@get
            }
            val activity = withContext(Dispatchers.IO) {
                access.db.partyActivity(clientCode, byTrader).map {
                    EmailActivityDto(it.year, it.month, it.placements)
                }
            }
            call.respond(activity)
        }

        get("/spots") {
            val access = call.staffAccessOrRespond(registry) ?: return@get
            val (year, month) = call.yearMonthOrRespond() ?: return@get
            val byTrader = call.byTraderOrRespond() ?: return@get
            val clientCode = call.request.queryParameters["clientCode"]
            if (clientCode.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "clientCode required"))
                return@get
            }
            val spots = withContext(Dispatchers.IO) {
                partyCommercials(access, year, month, byTrader)[clientCode].orEmpty()
                    .groupBy { it.spotId }
                    .map { (id, rows) -> EmailSpotDto(id, spotLabel(rows.first(), byTrader, clientCode), rows.size) }
                    .sortedByDescending { it.placements }
            }
            call.respond(spots)
        }

        get("/preview") {
            val access = call.staffAccessOrRespond(registry) ?: return@get
            val (year, month) = call.yearMonthOrRespond() ?: return@get
            val byTrader = call.byTraderOrRespond() ?: return@get
            val clientCode = call.request.queryParameters["clientCode"]
            if (clientCode.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "clientCode required"))
                return@get
            }
            val spotIds = call.request.queryParameters["spotIds"]
                ?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()
            val data = withContext(Dispatchers.IO) {
                assembleScheduleEmail(registry, access, year, month, clientCode, byTrader, spotIds,
                    call.request.queryParameters["personalMessage"])
            }
            if (data == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No spots for customer '$clientCode' this month"))
                return@get
            }
            call.respondText(renderScheduleEmail(data), ContentType.Text.Html)
        }

        post("/send") {
            val access = call.staffAccessOrRespond(registry) ?: return@post
            val operator = call.authUser().username
            val req = call.receive<SendScheduleEmailRequest>()
            if (req.month !in 1..12) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "month must be 1..12"))
                return@post
            }
            val byTrader = when (req.kind) {
                "customer" -> false
                "trader" -> true
                else -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "kind must be 'customer' or 'trader'"))
                    return@post
                }
            }
            val smtp = registry.config(access.grant.stationId)?.smtp ?: registry.defaultSmtp
            if (smtp == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "No SMTP settings configured - add an `smtp:` block (file-wide or on this station) in stations.yaml")
                )
                return@post
            }

            val result = withContext(Dispatchers.IO) {
                val customer = access.db.customerByCode(req.clientCode)
                    ?: return@withContext SendResult(false, "Unknown customer '${req.clientCode}' on this station")
                val to = req.to?.takeIf { it.isNotBlank() } ?: customer.email
                    ?: return@withContext SendResult(false, "Customer '${customer.name}' has no stored email - pass a recipient explicitly")

                val data = assembleScheduleEmail(registry, access, req.year, req.month, req.clientCode, byTrader, req.spotIds.toSet(), req.personalMessage)
                    ?: return@withContext SendResult(false, "No spots for customer '${req.clientCode}' this month")

                val subject = req.subject?.takeIf { it.isNotBlank() }
                    ?: "ΠΡΟΓΡΑΜΜΑΤΙΣΜΟΙ - ${data.mediumLabel} - ${greekMonth(req.month)} ${req.year}"
                val html = renderScheduleEmail(data)
                val transmissions = data.spots.sumOf { sec -> sec.rows.sumOf { r -> r.cells.sumOf { it?.count ?: 0 } } }

                // Send, then archive the attempt either way (audit trail).
                try {
                    SmtpMailer(smtp.toSettings()).sendHtml(to, subject, html)
                    access.db.logEmail(
                        StationDb.EmailLogEntry(
                            customerCode = req.clientCode, customerName = customer.name, recipient = to,
                            subject = subject, year = req.year, month = req.month,
                            spotCount = data.spots.size, transmissionCount = transmissions,
                            bodyHtml = html, sentBy = operator, status = "SENT",
                        )
                    )
                    SendResult(true, "Sent to $to (${data.spots.size} σποτ)", to = to, spots = data.spots.size)
                } catch (e: Exception) {
                    access.db.logEmail(
                        StationDb.EmailLogEntry(
                            customerCode = req.clientCode, customerName = customer.name, recipient = to,
                            subject = subject, year = req.year, month = req.month,
                            spotCount = data.spots.size, transmissionCount = transmissions,
                            bodyHtml = null, sentBy = operator, status = "FAILED", error = e.message,
                        )
                    )
                    SendResult(false, "Send failed: ${e.message}")
                }
            }
            if (result.ok) {
                call.respond(mapOf("status" to result.message, "to" to (result.to ?: ""), "spots" to result.spots.toString()))
            } else {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
            }
        }

        // ── audit trail ─────────────────────────────────────────────────
        get("/log") {
            val access = call.staffAccessOrRespond(registry) ?: return@get
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val clientCode = call.request.queryParameters["clientCode"]?.takeIf { it.isNotBlank() }
            val log = withContext(Dispatchers.IO) { access.db.recentEmailLog(limit, clientCode) }
            call.respond(log.map {
                EmailLogDto(
                    it.id, it.customerCode, it.customerName, it.recipient, it.subject,
                    it.year, it.month, it.spotCount, it.transmissionCount,
                    it.sentBy, it.sentAt, it.status, it.error,
                )
            })
        }

        // Re-view a logged email exactly as delivered.
        get("/log/{id}") {
            val access = call.staffAccessOrRespond(registry) ?: return@get
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "numeric id required"))
                return@get
            }
            val body = withContext(Dispatchers.IO) { access.db.emailLogBody(id) }
            if (body == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No archived body for entry $id"))
            } else {
                call.respondText(body, ContentType.Text.Html)
            }
        }
    }
}

private data class SendResult(val ok: Boolean, val message: String, val to: String? = null, val spots: Int = 0)

/** NORMAL_USER on the station required - emailing customers is staff work. */
private suspend fun io.ktor.server.application.ApplicationCall.staffAccessOrRespond(
    registry: StationRegistry,
): StationAccess? {
    val access = stationAccessOrRespond(registry) ?: return null
    if (access.grant.role != UserRole.NORMAL_USER) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "Requires full access on this station"))
        return null
    }
    return access
}

private fun io.ktor.server.application.ApplicationCall.yearMonthOrRespond(): Pair<Int, Int>? {
    val year = request.queryParameters["year"]?.toIntOrNull()
    val month = request.queryParameters["month"]?.toIntOrNull()
    return if (year == null || month == null || month !in 1..12) null else year to month
}

/**
 * `kind=customer` (the spot's owner, default) or `kind=trader` (the
 * contract's payer); null means a 400 was already sent. `false` is a valid
 * result, so callers may still use `?: return`.
 */
private suspend fun io.ktor.server.application.ApplicationCall.byTraderOrRespond(): Boolean? =
    when (request.queryParameters["kind"] ?: "customer") {
        "customer" -> false
        "trader" -> true
        else -> {
            respond(HttpStatusCode.BadRequest, mapOf("error" to "kind must be 'customer' or 'trader'"))
            null
        }
    }

/**
 * The month's commercials grouped by party code: each spot's own customer,
 * or (byTrader) the payer of its contract. Spots without a contract line
 * have no payer and can only be reached through their customer.
 */
private fun partyCommercials(
    access: StationAccess,
    year: Int,
    month: Int,
    byTrader: Boolean,
): Map<String, List<CommercialRow>> {
    access.db.ensureMonthSeeded(year, month)
    val (_, commercialsByKey) = access.db.loadMonth(year, month)
    return commercialsByKey.values.flatten()
        .groupBy { (if (byTrader) it.payerCode else it.clientCode).orEmpty() }
        .filterKeys { it.isNotEmpty() }
}

/**
 * In trader mode a spot may belong to a DIFFERENT end customer (the
 * "triangular" case: the agency pays, e.g. Unilever's spot airs) - label
 * the spot with whose it is so the operator and the email are unambiguous.
 */
private fun spotLabel(row: CommercialRow, byTrader: Boolean, partyCode: String): String =
    if (byTrader && row.clientCode != partyCode) "${row.message} — ${row.clientName}" else row.message

private fun SmtpConfig.toSettings() = SmtpSettings(
    host = host, port = port, username = username, password = password, from = from, startTls = startTls,
)

/**
 * Builds the multi-section email: one [SpotSection] per spot the party ran
 * that month (optionally restricted to [spotIds]), each a grid of just that
 * spot's placements with its own per-programme breakdown. The party is the
 * spot's own customer, or (byTrader) the payer of its contract - in the
 * triangular case the trader's email covers other companies' spots, each
 * section labelled with the end customer.
 */
private fun assembleScheduleEmail(
    registry: StationRegistry,
    access: StationAccess,
    year: Int,
    month: Int,
    clientCode: String,
    byTrader: Boolean,
    spotIds: Set<Long>,
    personalMessage: String?,
): ScheduleEmailData? {
    val customer = access.db.customerByCode(clientCode) ?: return null
    val stationName = registry.config(access.grant.stationId)?.name ?: access.grant.stationId

    access.db.ensureMonthSeeded(year, month)
    val (cells, commercialsByKey) = access.db.loadMonth(year, month)
    val colorByKey = cells.associate { (it.breakId to it.date) to it.zoneColorArgb }
    val breaks = access.db.loadBreaks()
    val days = YearMonth.of(year, month).lengthOfMonth()

    fun isMine(row: CommercialRow): Boolean =
        if (byTrader) row.payerCode == clientCode else row.clientCode == clientCode

    // (breakId,date) -> the party's rows there, filtered to the chosen spots
    fun rowsFor(spotId: Long): Map<Pair<Long, LocalDate>, List<CommercialRow>> =
        commercialsByKey.mapValues { (_, list) ->
            list.filter { isMine(it) && it.spotId == spotId }
        }.filterValues { it.isNotEmpty() }

    val allSpotIds = commercialsByKey.values.flatten()
        .filter { isMine(it) }
        .let { mine ->
            if (mine.isEmpty()) return null
            val wanted = if (spotIds.isEmpty()) mine.map { it.spotId }.toSet() else spotIds
            // preserve a stable, busiest-first order
            mine.filter { it.spotId in wanted }
                .groupBy { it.spotId }
                .entries.sortedByDescending { it.value.size }
                .map { it.key to spotLabel(it.value.first(), byTrader, clientCode) }
        }
    if (allSpotIds.isEmpty()) return null

    val sections = allSpotIds.map { (spotId, description) ->
        val mine = rowsFor(spotId)
        val usedBreaks = breaks.filter { b -> mine.keys.any { it.first == b.id } }
        val rows = usedBreaks.map { b ->
            EmailGridRow(
                label = b.label,
                cells = (1..days).map { day ->
                    val key = b.id to LocalDate.of(year, month, day)
                    mine[key]?.let { EmailCell(count = it.size, colorArgb = colorByKey[key]) }
                }
            )
        }
        val totals = mine.values.flatten()
            .groupBy { it.programName ?: it.type.ifBlank { "Λοιπά" } }
            .map { (name, rs) -> ProgramTotal(name, rs.firstNotNullOfOrNull { it.programColorArgb }, rs.size) }
        SpotSection(description = description, rows = rows, programTotals = totals)
    }

    return ScheduleEmailData(
        stationName = stationName,
        customerName = customer.name,
        year = year,
        month = month,
        personalMessage = personalMessage,
        spots = sections,
    )
}

private fun greekMonth(month: Int) = listOf(
    "Ιανουάριος", "Φεβρουάριος", "Μάρτιος", "Απρίλιος", "Μάιος", "Ιούνιος",
    "Ιούλιος", "Αύγουστος", "Σεπτέμβριος", "Οκτώβριος", "Νοέμβριος", "Δεκέμβριος",
)[month - 1]
