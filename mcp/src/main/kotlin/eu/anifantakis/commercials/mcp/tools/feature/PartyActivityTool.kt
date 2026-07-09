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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Months a party has airings on a station, newest first — the real signal for "when did X last air". */
object PartyActivityTool : McpTool {
    override val name = "party_activity"
    override val description =
        "The months a party has airings on a station, newest first, with placement counts. " +
            "Use to answer recency questions like 'when did customer X last air'."
    override val inputSchema = inputSchema(required = listOf("station", "code")) {
        prop("station", "string", "Station id.")
        prop("code", "string", "Client code (Κωδ. Πελ.).")
        prop("byTrader", "boolean", "Treat code as a contract payer (trader). Default false.")
    }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult =
        runTool(name) {
            val services = ctx.services
            val a = req.args
            val access = services.resolveStation(ctx.caller, a.stringOrNull("station"))
            val code = a.string("code")
            services.requireCode(access.grant, code)
            val months = access.data.partyActivity(code, a.bool("byTrader", false))
            buildJsonObject {
                put("code", code)
                put("monthsWithActivity", months.size)
                months.firstOrNull()
                    ?.let { put("lastAired", "%04d-%02d".format(it.year, it.month)) }
                put("activity", buildJsonArray {
                    months.forEach { m ->
                        addJsonObject {
                            put("year", m.year)
                            put("month", m.month)
                            put("placements", m.placements)
                        }
                    }
                })
            }
        }
}