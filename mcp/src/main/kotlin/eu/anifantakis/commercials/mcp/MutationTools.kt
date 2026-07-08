package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.mailer.SmtpMailer
import eu.anifantakis.commercials.mailer.renderScheduleEmail
import eu.anifantakis.commercials.mcp.ScheduleEmailAssembler.toSettings
import eu.anifantakis.commercials.server.scheduler.StationDb
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Registers the WRITE tools - only when [McpToolServices.mutationsEnabled] is
 * on. Every mutation:
 *  - requires NORMAL_USER on the station ([McpToolServices.requireStaff]),
 *  - takes `confirm` (default false = a dry-run PREVIEW, nothing is written),
 *  - is audit-logged when actually performed ([McpToolServices.audit]).
 */
internal fun Server.registerMutationTools(caller: McpCaller, services: McpToolServices) {

    addTool(
        name = "add_placement",
        description = "Append a spot to a break on a date, at the next free position. Requires full access. " +
            "confirm=false (default) returns a dry-run preview; confirm=true performs the write.",
        inputSchema = inputSchema(required = listOf("station", "date", "time", "spotId")) {
            prop("station", "string", "Station id.")
            prop("date", "string", "Date YYYY-MM-DD.")
            prop("time", "string", "Break time label HH:mm (e.g. 17:30).")
            prop("spotId", "integer", "Spot (creative) id to place. See contract_spots.")
            prop("confirm", "boolean", "Set true to perform the write. Default false = dry-run.")
        },
    ) { req ->
        runTool("add_placement") {
            val a = req.args
            val access = services.resolveStation(caller, a.stringOrNull("station"))
            services.requireStaff(access.grant)
            val date = parseIsoDate(a.string("date"))
            val time = a.string("time").trim()
            val spotId = a.long("spotId")
            val breakId = services.resolveBreak(access, time).id

            if (!a.bool("confirm", false)) {
                return@runTool dryRun("add_placement", buildJsonObject {
                    put("station", a.string("station")); put("date", date.toString())
                    put("break", time); put("spotId", spotId)
                })
            }
            val row = access.db.addPlacement(spotId, breakId, date)
                ?: throw McpToolException("Spot $spotId not found (or hidden) on this station.")
            services.audit(caller, "add_placement", "spot=$spotId break=$time date=$date -> placement=${row.id}")
            buildJsonObject {
                put("performed", true); put("action", "add_placement")
                put("placementId", row.id); put("position", row.position)
                put("spotId", row.spotId); put("message", row.message)
                put("clientName", row.clientName); put("durationSeconds", row.durationSeconds)
            }
        }
    }

    addTool(
        name = "delete_placement",
        description = "Remove a placement by id. Requires full access. confirm=false (default) previews; " +
            "confirm=true deletes.",
        inputSchema = inputSchema(required = listOf("station", "placementId")) {
            prop("station", "string", "Station id.")
            prop("placementId", "integer", "Placement id to delete (see spots_in_break).")
            prop("confirm", "boolean", "Set true to delete. Default false = dry-run.")
        },
    ) { req ->
        runTool("delete_placement") {
            val a = req.args
            val access = services.resolveStation(caller, a.stringOrNull("station"))
            services.requireStaff(access.grant)
            val placementId = a.long("placementId")

            if (!a.bool("confirm", false)) {
                return@runTool dryRun("delete_placement", buildJsonObject {
                    put("station", a.string("station")); put("placementId", placementId)
                })
            }
            val deleted = access.db.deletePlacement(placementId)
            if (!deleted) throw McpToolException("Placement $placementId not found (nothing deleted).")
            services.audit(caller, "delete_placement", "placement=$placementId")
            buildJsonObject { put("performed", true); put("action", "delete_placement"); put("placementId", placementId) }
        }
    }

    addTool(
        name = "reorder_placements",
        description = "Reorder the spots in a break on a date. orderedPlacementIds must be a permutation of the " +
            "cell's CURRENT placement ids (from spots_in_break). Requires full access. confirm=false (default) " +
            "previews; confirm=true applies.",
        inputSchema = inputSchema(required = listOf("station", "date", "time", "orderedPlacementIds")) {
            prop("station", "string", "Station id.")
            prop("date", "string", "Date YYYY-MM-DD.")
            prop("time", "string", "Break time label HH:mm (e.g. 17:30).")
            propArray("orderedPlacementIds", "integer", "Placement ids in the desired air order (a permutation of the cell's current ids).")
            prop("confirm", "boolean", "Set true to apply. Default false = dry-run.")
        },
    ) { req ->
        runTool("reorder_placements") {
            val a = req.args
            val access = services.resolveStation(caller, a.stringOrNull("station"))
            services.requireStaff(access.grant)
            val date = parseIsoDate(a.string("date"))
            val time = a.string("time").trim()
            val orderedIds = a.longList("orderedPlacementIds")
            val breakId = services.resolveBreak(access, time).id

            if (!a.bool("confirm", false)) {
                return@runTool dryRun("reorder_placements", buildJsonObject {
                    put("station", a.string("station")); put("date", date.toString())
                    put("break", time); put("count", orderedIds.size)
                })
            }
            val ok = access.db.reorderPlacements(breakId, date, orderedIds)
            if (!ok) {
                throw McpToolException(
                    "Order rejected: orderedPlacementIds must be exactly the cell's current placement ids " +
                        "(your view may be stale - re-run spots_in_break)."
                )
            }
            services.audit(caller, "reorder_placements", "break=$time date=$date ids=$orderedIds")
            buildJsonObject { put("performed", true); put("action", "reorder_placements"); put("count", orderedIds.size) }
        }
    }

    addTool(
        name = "send_schedule_email",
        description = "Send a customer's monthly schedule email (one section per spot). Requires full access. " +
            "confirm=false (default) renders a PREVIEW and does NOT send; confirm=true sends via the station's " +
            "SMTP settings and archives the attempt.",
        inputSchema = inputSchema(required = listOf("station", "year", "month", "clientCode")) {
            prop("station", "string", "Station id.")
            prop("year", "integer", "Year, e.g. 2026.")
            prop("month", "integer", "Month 1-12.")
            prop("clientCode", "string", "Client code (Κωδ. Πελ.).")
            prop("kind", "string", "'customer' (spot owner, default) or 'trader' (contract payer).", enum = listOf("customer", "trader"))
            propArray("spotIds", "integer", "Restrict to these spot ids; omit for all of the party's spots.")
            prop("to", "string", "Recipient override; defaults to the customer's stored email.")
            prop("subject", "string", "Subject override; defaults to the legacy-style subject.")
            prop("personalMessage", "string", "Optional free-text note shown above the sections.")
            prop("confirm", "boolean", "Set true to actually SEND. Default false = dry-run (renders, does not send).")
        },
    ) { req ->
        runTool("send_schedule_email") {
            val a = req.args
            val access = services.resolveStation(caller, a.stringOrNull("station"))
            services.requireStaff(access.grant)
            val year = a.int("year")
            val month = a.int("month")
            if (month !in 1..12) throw McpToolException("month must be 1..12")
            val clientCode = a.string("clientCode")
            val byTrader = when (a.stringOrNull("kind") ?: "customer") {
                "customer" -> false
                "trader" -> true
                else -> throw McpToolException("kind must be 'customer' or 'trader'")
            }
            val spotIds = a.longListOrNull("spotIds")?.toSet().orEmpty()

            val data = ScheduleEmailAssembler.assemble(
                db = access.db,
                stationName = services.stationName(access.grant.stationId),
                year = year, month = month, clientCode = clientCode, byTrader = byTrader,
                spotIds = spotIds, personalMessage = a.stringOrNull("personalMessage"),
            ) ?: throw McpToolException(
                "No spots for '$clientCode' in $month/$year (kind=${if (byTrader) "trader" else "customer"})."
            )

            val recipient = a.stringOrNull("to")?.takeIf { it.isNotBlank() }
                ?: access.db.customerByCode(clientCode)?.email
                ?: throw McpToolException("Customer '${data.customerName}' has no stored email - pass 'to' explicitly.")
            val subject = a.stringOrNull("subject")?.takeIf { it.isNotBlank() }
                ?: "ΠΡΟΓΡΑΜΜΑΤΙΣΜΟΙ - ${data.mediumLabel} - ${ScheduleEmailAssembler.greekMonths[month - 1]} $year"
            val html = renderScheduleEmail(data)
            val transmissions = data.spots.sumOf { s -> s.rows.sumOf { r -> r.cells.sumOf { it?.count ?: 0 } } }

            if (!a.bool("confirm", false)) {
                return@runTool dryRun("send_schedule_email", buildJsonObject {
                    put("station", a.string("station")); put("recipient", recipient); put("subject", subject)
                    put("year", year); put("month", month); put("clientCode", clientCode)
                    put("spotSections", data.spots.size); put("transmissions", transmissions); put("htmlBytes", html.length)
                })
            }

            val smtp = services.smtpFor(access.grant.stationId)
                ?: throw McpToolException("No SMTP configured - add an smtp: block (file-wide or on this station) in server.yaml.")
            try {
                SmtpMailer(smtp.toSettings()).sendHtml(recipient, subject, html)
                access.db.logEmail(
                    StationDb.EmailLogEntry(
                        customerCode = clientCode, customerName = data.customerName, recipient = recipient,
                        subject = subject, year = year, month = month,
                        spotCount = data.spots.size, transmissionCount = transmissions,
                        bodyHtml = html, sentBy = caller.user.username, status = "SENT",
                    )
                )
                services.audit(caller, "send_schedule_email", "to=$recipient client=$clientCode $month/$year sections=${data.spots.size}")
                buildJsonObject {
                    put("performed", true); put("action", "send_schedule_email")
                    put("recipient", recipient); put("subject", subject)
                    put("spotSections", data.spots.size); put("transmissions", transmissions)
                }
            } catch (e: Exception) {
                access.db.logEmail(
                    StationDb.EmailLogEntry(
                        customerCode = clientCode, customerName = data.customerName, recipient = recipient,
                        subject = subject, year = year, month = month,
                        spotCount = data.spots.size, transmissionCount = transmissions,
                        bodyHtml = null, sentBy = caller.user.username, status = "FAILED", error = e.message,
                    )
                )
                throw McpToolException("Send failed (logged): ${e.message}")
            }
        }
    }
}
