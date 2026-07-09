package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mcp.McpToolException
import eu.anifantakis.commercials.mcp.args
import eu.anifantakis.commercials.mcp.dryRun
import eu.anifantakis.commercials.mcp.inputSchema
import eu.anifantakis.commercials.mcp.prop
import eu.anifantakis.commercials.mcp.runTool
import eu.anifantakis.commercials.mcp.tools.McpTool
import eu.anifantakis.commercials.mcp.tools.ToolContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** WRITE: remove a placement by id. Staff-only, dry-run unless confirm=true, audited. */
object DeletePlacementTool : McpTool {
    override val name = "delete_placement"
    override val mutating = true
    override val description =
        "Remove a placement by id. Requires full access. confirm=false (default) previews; " +
            "confirm=true deletes."
    override val inputSchema = inputSchema(required = listOf("station", "placementId")) {
        prop("station", "string", "Station id.")
        prop("placementId", "integer", "Placement id to delete (see spots_in_break).")
        prop("confirm", "boolean", "Set true to delete. Default false = dry-run.")
    }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult =
        runTool(name) {
            val services = ctx.services
            val caller = ctx.caller
            val a = req.args
            val access = services.resolveStation(caller, a.stringOrNull("station"))
            services.requireStaff(access.grant)
            val placementId = a.long("placementId")

            if (!a.bool("confirm", false)) {
                return@runTool dryRun("delete_placement", buildJsonObject {
                    put("station", a.string("station")); put("placementId", placementId)
                })
            }
            val deleted = access.data.deletePlacement(placementId)
            if (!deleted) throw McpToolException("Placement $placementId not found (nothing deleted).")
            services.audit(caller, "delete_placement", "placement=$placementId")
            buildJsonObject {
                put("performed", true); put(
                "action",
                "delete_placement"
            ); put("placementId", placementId)
            }
        }
}