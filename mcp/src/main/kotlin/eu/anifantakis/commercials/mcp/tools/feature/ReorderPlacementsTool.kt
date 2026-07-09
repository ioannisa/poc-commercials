package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mcp.McpToolException
import eu.anifantakis.commercials.mcp.args
import eu.anifantakis.commercials.mcp.dryRun
import eu.anifantakis.commercials.mcp.inputSchema
import eu.anifantakis.commercials.mcp.parseIsoDate
import eu.anifantakis.commercials.mcp.prop
import eu.anifantakis.commercials.mcp.propArray
import eu.anifantakis.commercials.mcp.runTool
import eu.anifantakis.commercials.mcp.tools.McpTool
import eu.anifantakis.commercials.mcp.tools.ToolContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** WRITE: reorder the spots in a break. Staff-only, dry-run unless confirm=true, audited. */
object ReorderPlacementsTool : McpTool {
    override val name = "reorder_placements"
    override val mutating = true
    override val description =
        "Reorder the spots in a break on a date. orderedPlacementIds must be a permutation of the " +
            "cell's CURRENT placement ids (from spots_in_break). Requires full access. confirm=false (default) " +
            "previews; confirm=true applies."
    override val inputSchema =
        inputSchema(required = listOf("station", "date", "time", "orderedPlacementIds")) {
            prop("station", "string", "Station id.")
            prop("date", "string", "Date YYYY-MM-DD.")
            prop("time", "string", "Break time label HH:mm (e.g. 17:30).")
            propArray(
                "orderedPlacementIds",
                "integer",
                "Placement ids in the desired air order (a permutation of the cell's current ids)."
            )
            prop("confirm", "boolean", "Set true to apply. Default false = dry-run.")
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
            val orderedIds = a.longList("orderedPlacementIds")
            val breakId = services.resolveBreak(access, time).id

            if (!a.bool("confirm", false)) {
                return@runTool dryRun("reorder_placements", buildJsonObject {
                    put("station", a.string("station")); put("date", date.toString())
                    put("break", time); put("count", orderedIds.size)
                })
            }
            val ok = access.data.reorderPlacements(breakId, date, orderedIds)
            if (!ok) {
                throw McpToolException(
                    "Order rejected: orderedPlacementIds must be exactly the cell's current placement ids " +
                            "(your view may be stale - re-run spots_in_break)."
                )
            }
            services.audit(caller, "reorder_placements", "break=$time date=$date ids=$orderedIds")
            buildJsonObject {
                put("performed", true); put(
                "action",
                "reorder_placements"
            ); put("count", orderedIds.size)
            }
        }
}