package eu.anifantakis.commercials.mcp.tools.feature

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

/** Substring search over a station's parties (spot owners, or contract payers with byTrader), busiest first. */
object SearchPartiesTool : McpTool {
    override val name = "search_parties"
    override val description =
        "Search customers/traders on a station by name or client code (substring), busiest " +
            "first, with all-time spot and placement counts. Set byTrader=true to search contract-paying " +
            "parties (agencies) instead of spot owners."
    override val inputSchema = inputSchema(required = listOf("station", "query")) {
        prop("station", "string", "Station id (see list_stations).")
        prop("query", "string", "Name or client-code substring.")
        prop(
            "byTrader",
            "boolean",
            "Search contract payers (traders) instead of spot owners. Default false."
        )
    }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult =
        runTool(name) {
            val services = ctx.services
            val a = req.args
            val access = services.resolveStation(ctx.caller, a.stringOrNull("station"))
            var rows = access.data.searchParties(a.string("query"), a.bool("byTrader", false))
            if (services.isCustomerScoped(access.grant)) {
                rows = rows.filter { it.code == access.grant.clientCode }
            }
            buildJsonArray {
                rows.forEach { p ->
                    addJsonObject {
                        put("code", p.code)
                        put("name", p.name)
                        p.email?.let { put("email", it) }
                        p.vatNumber?.let { put("vatNumber", it) }
                        p.phone?.let { put("phone", it) }
                        put("spotCount", p.spotCount)
                        put("placementCount", p.placementCount)
                    }
                }
            }
        }
}