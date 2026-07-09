package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mcp.args
import eu.anifantakis.commercials.mcp.inputSchema
import eu.anifantakis.commercials.mcp.prop
import eu.anifantakis.commercials.mcp.runTool
import eu.anifantakis.commercials.mcp.tools.McpTool
import eu.anifantakis.commercials.mcp.tools.ToolContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Quick footprint of a station's data: total placements and the earliest/latest air dates. */
object StationFootprintTool : McpTool {
    override val name = "station_footprint"
    override val description =
        "Quick footprint of a station's scheduling data: total placements and the " +
            "earliest/latest air dates."
    override val inputSchema = inputSchema(required = listOf("station")) {
        prop("station", "string", "Station id.")
    }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult =
        runTool(name) {
            val access = ctx.services.resolveStation(ctx.caller, req.args.stringOrNull("station"))
            val stats = access.data.placementStats()
            buildJsonObject {
                put("placements", stats.placements)
                stats.minDate?.let { put("firstAirDate", it) }
                stats.maxDate?.let { put("lastAirDate", it) }
            }
        }
}