package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.mailer.SmtpMailer
import eu.anifantakis.commercials.mailer.renderScheduleEmail
import eu.anifantakis.commercials.scheduleemail.ScheduleEmailAssembler
import eu.anifantakis.commercials.scheduleemail.ScheduleEmailAssembler.toSettings
import eu.anifantakis.commercials.scheduleemail.asScheduleEmailSource
import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.plugins.StationAccess
import eu.anifantakis.commercials.server.plugins.authUser
import eu.anifantakis.commercials.server.plugins.stationAccessOrRespond
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.scheduler.StationDb
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class EmailCustomerDto(
    val code: String,
    val name: String,
    val email: String?,
    val spotCount: Int,
    val placementCount: Int,
    val vatNumber: String? = null,
    val phone: String? = null,
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
    // Party search is MASTER-DATA (customers/traders), not an email concern -
    // the neutral path is the canonical one; /api/email/schedule/customers
    // below remains as the historical alias.
    route("/api/parties") {
        get("/search") {
            val access = call.staffAccessOrRespond(registry) ?: return@get
            val byTrader = call.byTraderOrRespond() ?: return@get
            val q = call.request.queryParameters["q"]?.trim().orEmpty()
            if (q.length < 3) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "q with at least 3 characters required"))
                return@get
            }
            val parties = withContext(Dispatchers.IO) {
                access.db.searchParties(q, byTrader).map {
                    EmailCustomerDto(
                        code = it.code, name = it.name, email = it.email,
                        spotCount = it.spotCount, placementCount = it.placementCount,
                        vatNumber = it.vatNumber, phone = it.phone,
                    )
                }
            }
            call.respond(parties)
        }
    }

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
                        vatNumber = it.vatNumber,
                        phone = it.phone,
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
                    .map { (id, rows) -> EmailSpotDto(id, ScheduleEmailAssembler.spotLabel(rows.first(), byTrader, clientCode), rows.size) }
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
                access.db.ensureMonthSeeded(year, month)
                ScheduleEmailAssembler.assemble(
                    access.db.asScheduleEmailSource(),
                    registry.config(access.grant.stationId)?.name ?: access.grant.stationId,
                    year, month, clientCode, byTrader, spotIds, call.request.queryParameters["personalMessage"],
                )
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
            // station -> group -> file-wide (each outlet traditionally sent from
            // its own address, but siblings can share the group's).
            val smtp = registry.smtpFor(access.grant.stationId)
            if (smtp == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "No SMTP settings configured - add an `smtp:` block (file-wide or on this station) in server.yaml")
                )
                return@post
            }

            val result = withContext(Dispatchers.IO) {
                val customer = access.db.customerByCode(req.clientCode)
                    ?: return@withContext SendResult(false, "Unknown customer '${req.clientCode}' on this station")
                // TEST redirect (server.yaml `emailRedirectTo`): every send is delivered
                // THERE, never to the real customer. The intended address is preserved
                // in the subject and the audit log so a test run stays traceable.
                val redirectTo = registry.emailRedirectTo
                val intendedTo = req.to?.takeIf { it.isNotBlank() } ?: customer.email
                if (intendedTo == null && redirectTo == null) {
                    return@withContext SendResult(false, "Customer '${customer.name}' has no stored email - pass a recipient explicitly")
                }
                val to = redirectTo ?: intendedTo!!

                access.db.ensureMonthSeeded(req.year, req.month)
                val data = ScheduleEmailAssembler.assemble(
                    access.db.asScheduleEmailSource(),
                    registry.config(access.grant.stationId)?.name ?: access.grant.stationId,
                    req.year, req.month, req.clientCode, byTrader, req.spotIds.toSet(), req.personalMessage,
                )
                    ?: return@withContext SendResult(false, "No spots for customer '${req.clientCode}' this month")

                val baseSubject = req.subject?.takeIf { it.isNotBlank() }
                    ?: "ΠΡΟΓΡΑΜΜΑΤΙΣΜΟΙ - ${data.mediumLabel} - ${ScheduleEmailAssembler.greekMonths[req.month - 1]} ${req.year}"
                val subject = if (redirectTo != null) "[TEST → ${intendedTo ?: "χωρίς διεύθυνση"}] $baseSubject" else baseSubject
                val html = renderScheduleEmail(data)
                val transmissions = data.spots.sumOf { sec -> sec.rows.sumOf { r -> r.cells.sumOf { it?.count ?: 0 } } }

                // Send, then archive the attempt either way (audit trail) - UNLESS
                // this was a TEST redirect. A redirected send must leave no trace: its
                // recipient is the test address, and logging it would feed that fake
                // into the audit trail, the next-month smart pre-fill and any
                // history-based email recovery - corrupting real customer data.
                try {
                    SmtpMailer(smtp.toSettings()).sendHtml(to, subject, html)
                    if (redirectTo == null) {
                        access.db.logEmail(
                            StationDb.EmailLogEntry(
                                customerCode = req.clientCode, customerName = customer.name, recipient = to,
                                subject = subject, year = req.year, month = req.month,
                                spotCount = data.spots.size, transmissionCount = transmissions,
                                bodyHtml = html, sentBy = operator, status = "SENT",
                            )
                        )
                    }
                    val note = if (redirectTo != null) " (TEST redirect - intended $intendedTo, not logged)" else ""
                    SendResult(true, "Sent to $to$note (${data.spots.size} σποτ)", to = to, spots = data.spots.size)
                } catch (e: Exception) {
                    if (redirectTo == null) {
                        access.db.logEmail(
                            StationDb.EmailLogEntry(
                                customerCode = req.clientCode, customerName = customer.name, recipient = to,
                                subject = subject, year = req.year, month = req.month,
                                spotCount = data.spots.size, transmissionCount = transmissions,
                                bodyHtml = null, sentBy = operator, status = "FAILED", error = e.message,
                            )
                        )
                    }
                    SendResult(false, "Send failed: ${e.message}")
                }
            }
            if (result.ok) {
                call.respond(mapOf("status" to result.message, "to" to (result.to ?: ""), "spots" to result.spots.toString()))
            } else {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
            }
        }

        // Connectivity smoke test: send a tiny email through the resolved SMTP,
        // WITHOUT touching any customer or month. Goes to `emailRedirectTo` if set,
        // else to a `?to=` address. Proves the server.yaml `smtp:` block actually
        // reaches the mail server and authenticates - the failure message is the
        // provider's own (e.g. Office 365's "SmtpClientAuthentication is disabled").
        post("/test-smtp") {
            val access = call.staffAccessOrRespond(registry) ?: return@post
            val smtp = registry.smtpFor(access.grant.stationId)
            if (smtp == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No SMTP settings - add an `smtp:` block in server.yaml"))
                return@post
            }
            val to = registry.emailRedirectTo ?: call.request.queryParameters["to"]?.trim()?.takeIf { it.isNotBlank() }
            if (to == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Provide ?to=<address> or set emailRedirectTo in server.yaml"))
                return@post
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    SmtpMailer(smtp.toSettings()).sendHtml(
                        to = to,
                        subject = "Commercials Manager — SMTP test",
                        html = "<p>SMTP test from <b>${smtp.from}</b> via <b>${smtp.host}:${smtp.port}</b>. " +
                            "If you are reading this, outgoing mail works.</p>",
                    )
                    "OK" to "Test email sent to $to via ${smtp.host}"
                } catch (e: Exception) {
                    "ERR" to "SMTP send failed: ${e.message}"
                }
            }
            if (result.first == "OK") call.respond(mapOf("status" to result.second))
            else call.respond(HttpStatusCode.BadGateway, mapOf("error" to result.second))
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

// Schedule-email assembly (assembleScheduleEmail, spotLabel, SmtpConfig.toSettings,
// greek month names) now lives in the shared :schedule-email module so the REST
// route and the MCP send_schedule_email tool share one implementation.
