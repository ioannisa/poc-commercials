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
import eu.anifantakis.commercials.server.scheduler.AddPlacementResult
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
            "confirm=false (default) returns a dry-run preview; confirm=true performs the write. " +
            "A WHITE cell (no break there, or an unpainted one) also needs programId - the first " +
            "spot paints the break; a painted break ignores programId and stamps its own " +
            "programme on the spot."
    override val inputSchema = inputSchema(required = listOf("station", "date", "time", "spotId")) {
        prop("station", "string", "Station id.")
        prop("date", "string", "Date YYYY-MM-DD.")
        prop("time", "string", "Break time label HH:mm (e.g. 17:30).")
        prop("spotId", "integer", "Spot (creative) id to place. See contract_spots.")
        prop("programId", "integer", "Programme id that paints a WHITE cell's break on the first spot. Ignored when the break is painted.")
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
            val programId = a.longOrNull("programId")
            val breakTime = services.parseBreakTime(time)

            if (!a.bool("confirm", false)) {
                return@runTool dryRun("add_placement", buildJsonObject {
                    put("station", a.string("station")); put("date", date.toString())
                    put("break", time); put("spotId", spotId)
                    programId?.let { put("programId", it) }
                })
            }
            val row = when (val result = access.data.addPlacement(spotId, breakTime, date, programId)) {
                is AddPlacementResult.UnknownSpot ->
                    throw McpToolException("Spot $spotId not found (or hidden) on this station.")
                is AddPlacementResult.ProgramRequired ->
                    throw McpToolException(
                        "The $time cell on $date has no programme. The FIRST spot into a white " +
                            "cell paints its break - pass programId to say which programme."
                    )
                is AddPlacementResult.UnknownProgram ->
                    throw McpToolException("Programme $programId not found (or hidden) on this station.")
                is AddPlacementResult.Added -> result.row
            }
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