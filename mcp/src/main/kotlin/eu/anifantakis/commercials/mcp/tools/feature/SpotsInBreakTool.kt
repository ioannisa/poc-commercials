package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mcp.args
import eu.anifantakis.commercials.mcp.inputSchema
import eu.anifantakis.commercials.mcp.parseIsoDate
import eu.anifantakis.commercials.mcp.prop
import eu.anifantakis.commercials.mcp.runTool
import eu.anifantakis.commercials.mcp.tools.McpTool
import eu.anifantakis.commercials.mcp.tools.ToolContext
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Break Console's gift marker (client StringKey DETAIL_GIFT_SUFFIX). */
private const val GIFT_MARKER = "ΔΩΡΑ"

/**
 * The Break Console's **Τύπος** for a spot: the ERP sales item, exactly like
 * CommercialDetailScreen. The programme ([CommercialRow.type], e.g. ΚΛΕΨΑ /
 * ΞΕΝΗ ΤΑΙΝΙΑ) is NOT the spot's type - falling back to it is a category error
 * ("a programme is not a product"), so it is never used. A gift with no ERP
 * item shows the gift marker; an ERP-uncovered document is `null` (unknown).
 */
internal fun CommercialRow.breakConsoleType(): String? =
    salesItem ?: if (isGift) GIFT_MARKER else null

/** The spots scheduled in a specific break on a specific date, in air order. */
object SpotsInBreakTool : McpTool {
    override val name = "spots_in_break"
    override val description =
        "The spots scheduled in a specific break on a specific date, in air order. " +
            "time is the break label 'HH:mm' (e.g. '17:30'); date is 'YYYY-MM-DD'."
    override val inputSchema = inputSchema(required = listOf("station", "date", "time")) {
        prop("station", "string", "Station id.")
        prop("date", "string", "Date as YYYY-MM-DD.")
        prop("time", "string", "Break time label HH:mm (e.g. 17:30).")
    }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult =
        runTool(name) {
            val services = ctx.services
            val a = req.args
            val access = services.resolveStation(ctx.caller, a.stringOrNull("station"))
            val date = parseIsoDate(a.string("date"))
            val time = a.string("time").trim()
            val spots = services.breakSpots(access, date, time)
            buildJsonObject {
                put("date", date.toString())
                put("break", time)
                put("spotCount", spots.size)
                put("totalDurationSeconds", spots.sumOf { it.durationSeconds })
                put("spots", buildJsonArray {
                    spots.forEach { s ->
                        addJsonObject {
                            put("position", s.position)
                            put("clientCode", s.clientCode)
                            put("clientName", s.clientName)
                            put("message", s.message)
                            put("durationSeconds", s.durationSeconds)
                            // "type" = the Break Console's Τύπος (see
                            // breakConsoleType) - the ERP sales item, NEVER the
                            // programme.
                            s.breakConsoleType()?.let { put("type", it) }
                            put("contract", s.contract)
                            put("isGift", s.isGift)
                            put("flow", s.flow)
                            s.programName?.let { put("programName", it) }
                        }
                    }
                })
            }
        }
}