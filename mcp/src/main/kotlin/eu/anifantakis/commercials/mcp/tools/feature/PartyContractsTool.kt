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

/** The contract lines of a party (contract/line numbers, gift flag, entry date, spot/placement stats). */
object PartyContractsTool : McpTool {
    override val name = "party_contracts"
    override val description =
        "The contract lines of a party on a station: contract number, line number, gift flag, " +
            "entry date, and spot/placement/aired-seconds stats."
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
            val lines = access.data.partyContractLines(code, a.bool("byTrader", false))
            buildJsonArray {
                lines.forEach { l ->
                    addJsonObject {
                        put("lineId", l.lineId)
                        put("contractNumber", l.contractNumber)
                        put("lineNo", l.lineNo)
                        put("isGift", l.isGift)
                        put("desiredQty", l.desiredQty)
                        put("spotCount", l.spotCount)
                        put("placements", l.placements)
                        put("totalSeconds", l.totalSeconds)
                        l.entryDate?.let { put("entryDate", it) }
                    }
                }
            }
        }
}