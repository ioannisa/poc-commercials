package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mcp.McpToolException
import eu.anifantakis.commercials.mcp.args
import eu.anifantakis.commercials.mcp.inputSchema
import eu.anifantakis.commercials.mcp.prop
import eu.anifantakis.commercials.mcp.runTool
import eu.anifantakis.commercials.mcp.tools.McpTool
import eu.anifantakis.commercials.mcp.tools.ToolContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/** The spots (creatives) on a contract line (lineId from party_contracts). Not for customer-scoped callers. */
object ContractSpotsTool : McpTool {
    override val name = "contract_spots"
    override val description =
        "The spots (creatives) on a contract line (lineId comes from party_contracts): " +
            "description, duration, placement count, aired seconds."
    override val inputSchema = inputSchema(required = listOf("station", "lineId")) {
        prop("station", "string", "Station id.")
        prop("lineId", "integer", "Contract line id (from party_contracts).")
    }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult =
        runTool(name) {
            val services = ctx.services
            val a = req.args
            val access = services.resolveStation(ctx.caller, a.stringOrNull("station"))
            if (services.isCustomerScoped(access.grant)) {
                throw McpToolException(
                    "Customer-scoped access cannot browse contract lines by id; " +
                            "use party_contracts for your own client code."
                )
            }
            val spots = access.data.contractLineSpots(a.long("lineId"))
            buildJsonArray {
                spots.forEach { s ->
                    addJsonObject {
                        put("spotId", s.spotId)
                        put("description", s.description)
                        put("durationSeconds", s.durationSeconds)
                        put("placements", s.placements)
                        put("totalSeconds", s.totalSeconds)
                    }
                }
            }
        }
}