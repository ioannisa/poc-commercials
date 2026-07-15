package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mailer.renderScheduleEmail
import eu.anifantakis.commercials.mcp.McpToolException
import eu.anifantakis.commercials.mcp.args
import eu.anifantakis.commercials.mcp.dryRun
import eu.anifantakis.commercials.mcp.inputSchema
import eu.anifantakis.commercials.mcp.prop
import eu.anifantakis.commercials.mcp.propArray
import eu.anifantakis.commercials.mcp.runTool
import eu.anifantakis.commercials.mcp.tools.McpTool
import eu.anifantakis.commercials.mcp.tools.ToolContext
import eu.anifantakis.commercials.scheduleemail.ScheduleEmailAssembler
import eu.anifantakis.commercials.server.scheduler.StationDb
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * WRITE: send a customer's monthly schedule email. Staff-only; confirm=false
 * renders a preview without sending; confirm=true sends via the station's SMTP
 * and archives the attempt (SENT/FAILED). Reuses the shared [eu.anifantakis.commercials.scheduleemail.ScheduleEmailAssembler].
 */
object SendScheduleEmailTool : McpTool {
    override val name = "send_schedule_email"
    override val mutating = true
    override val description =
        "Send a customer's monthly schedule email (one section per spot). Requires full access. " +
            "confirm=false (default) renders a PREVIEW and does NOT send; confirm=true sends via the station's " +
            "SMTP settings and archives the attempt."
    override val inputSchema =
        inputSchema(required = listOf("station", "year", "month", "clientCode")) {
            prop("station", "string", "Station id.")
            prop("year", "integer", "Year, e.g. 2026.")
            prop("month", "integer", "Month 1-12.")
            prop("clientCode", "string", "Client code (Κωδ. Πελ.).")
            prop(
                "kind",
                "string",
                "'customer' (spot owner, default) or 'trader' (contract payer).",
                enum = listOf("customer", "trader")
            )
            propArray(
                "spotIds",
                "integer",
                "Restrict to these spot ids; omit for all of the party's spots."
            )
            prop("to", "string", "Recipient override; defaults to the customer's stored email.")
            prop("subject", "string", "Subject override; defaults to the legacy-style subject.")
            prop("personalMessage", "string", "Optional free-text note shown above the sections.")
            prop(
                "confirm",
                "boolean",
                "Set true to actually SEND. Default false = dry-run (renders, does not send)."
            )
        }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult =
        runTool(name) {
            val services = ctx.services
            val caller = ctx.caller
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
                source = access.data,
                stationName = services.stationName(access.grant.stationId),
                year = year, month = month, clientCode = clientCode, byTrader = byTrader,
                spotIds = spotIds, personalMessage = a.stringOrNull("personalMessage"),
            ) ?: throw McpToolException(
                "No spots for '$clientCode' in $month/$year (kind=${if (byTrader) "trader" else "customer"})."
            )

            // Same TEST redirect as the REST route: while server.yaml sets
            // emailRedirectTo, every send is delivered there, never to the real
            // customer. The intended address stays in the subject and the log.
            val redirectTo = services.emailRedirectTo
            val intendedTo = a.stringOrNull("to")?.takeIf { it.isNotBlank() }
                ?: access.data.customerByCode(clientCode)?.email
            if (intendedTo == null && redirectTo == null) {
                throw McpToolException("Customer '${data.customerName}' has no stored email - pass 'to' explicitly.")
            }
            val recipient = redirectTo ?: intendedTo!!
            val baseSubject = a.stringOrNull("subject")?.takeIf { it.isNotBlank() }
                ?: "ΠΡΟΓΡΑΜΜΑΤΙΣΜΟΙ - ${data.mediumLabel} - ${ScheduleEmailAssembler.greekMonths[month - 1]} $year"
            val subject = if (redirectTo != null) "[TEST → ${intendedTo ?: "χωρίς διεύθυνση"}] $baseSubject" else baseSubject
            val html = renderScheduleEmail(data)
            val transmissions =
                data.spots.sumOf { s -> s.rows.sumOf { r -> r.cells.sumOf { it?.count ?: 0 } } }

            if (!a.bool("confirm", false)) {
                return@runTool dryRun("send_schedule_email", buildJsonObject {
                    put("station", a.string("station")); put("recipient", recipient); put(
                    "subject",
                    subject
                )
                    put("year", year); put("month", month); put("clientCode", clientCode)
                    put("spotSections", data.spots.size); put(
                    "transmissions",
                    transmissions
                ); put("htmlBytes", html.length)
                })
            }

            val smtp = services.smtpFor(access.grant.stationId)
                ?: throw McpToolException("No SMTP configured - add an smtp: block (file-wide or on this station) in server.yaml.")
            try {
                services.sendEmail(smtp, recipient, subject, html)
                access.data.logEmail(
                    StationDb.EmailLogEntry(
                        customerCode = clientCode,
                        customerName = data.customerName,
                        recipient = recipient,
                        subject = subject,
                        year = year,
                        month = month,
                        spotCount = data.spots.size,
                        transmissionCount = transmissions,
                        bodyHtml = html,
                        sentBy = caller.user.username,
                        status = "SENT",
                    )
                )
                services.audit(
                    caller,
                    "send_schedule_email",
                    "to=$recipient client=$clientCode $month/$year sections=${data.spots.size}"
                )
                buildJsonObject {
                    put("performed", true); put("action", "send_schedule_email")
                    put("recipient", recipient); put("subject", subject)
                    put("spotSections", data.spots.size); put("transmissions", transmissions)
                }
            } catch (e: Exception) {
                access.data.logEmail(
                    StationDb.EmailLogEntry(
                        customerCode = clientCode,
                        customerName = data.customerName,
                        recipient = recipient,
                        subject = subject,
                        year = year,
                        month = month,
                        spotCount = data.spots.size,
                        transmissionCount = transmissions,
                        bodyHtml = null,
                        sentBy = caller.user.username,
                        status = "FAILED",
                        error = e.message,
                    )
                )
                throw McpToolException("Send failed (logged): ${e.message}")
            }
        }
}