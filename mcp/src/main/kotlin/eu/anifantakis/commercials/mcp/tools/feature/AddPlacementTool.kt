package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mcp.McpToolException
import eu.anifantakis.commercials.mcp.args
import eu.anifantakis.commercials.mcp.dryRun
import eu.anifantakis.commercials.mcp.inputSchema
import eu.anifantakis.commercials.mcp.parseIsoDate
import eu.anifantakis.commercials.mcp.prop
import eu.anifantakis.commercials.mcp.runTool
import eu.anifantakis.commercials.mcp.tools.McpTool
import eu.anifantakis.commercials.mcp.tools.ToolContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** WRITE: append a spot to a break on a date. Staff-only, dry-run unless confirm=true, audited. */
object AddPlacementTool : McpTool {
    override val name = "add_placement"
    override val mutating = true
    override val description =
        "Append a spot to a break on a date, at the next free position. Requires full access. " +
            "confirm=false (default) returns a dry-run preview; confirm=true performs the write."
    override val inputSchema = inputSchema(required = listOf("station", "date", "time", "spotId")) {
        prop("station", "string", "Station id.")
        prop("date", "string", "Date YYYY-MM-DD.")
        prop("time", "string", "Break time label HH:mm (e.g. 17:30).")
        prop("spotId", "integer", "Spot (creative) id to place. See contract_spots.")
        prop("confirm", "boolean", "Set true to perform the write. Default false = dry-run.")
    }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult =
        runTool(name) {
            val services = ctx.services
            val caller = ctx.caller
            val a = req.args
            val access = services.resolveStation(caller, a.stringOrNull("station"))
            services.requireStaff(access.grant)
            val date = parseIsoDate(a.string("date"))
            val time = a.string("time").trim()
            val spotId = a.long("spotId")
            val breakTime = services.parseBreakTime(time)

            if (!a.bool("confirm", false)) {
                return@runTool dryRun("add_placement", buildJsonObject {
                    put("station", a.string("station")); put("date", date.toString())
                    put("break", time); put("spotId", spotId)
                })
            }
            val row = access.data.addPlacement(spotId, breakTime, date)
                ?: throw McpToolException("Spot $spotId not found (or hidden) on this station.")
            services.audit(
                caller,
                "add_placement",
                "spot=$spotId break=$time date=$date -> placement=${row.id}"
            )
            buildJsonObject {
                put("performed", true); put("action", "add_placement")
                put("placementId", row.id); put("position", row.position)
                put("spotId", row.spotId); put("message", row.message)
                put("clientName", row.clientName); put("durationSeconds", row.durationSeconds)
            }
        }
}