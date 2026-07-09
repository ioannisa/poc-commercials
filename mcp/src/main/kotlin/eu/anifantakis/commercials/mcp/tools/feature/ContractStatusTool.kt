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

/**
 * Contract period + renewal status. Period dates are PROVISIONAL (placement-derived)
 * until an ERP import supplies real values; the tool says so and leans on lastAired.
 */
object ContractStatusTool : McpTool {
    override val name = "contract_status"
    override val description =
        "Contract period + renewal status for a party on a station, with each contract's aired " +
            "range. IMPORTANT: start/end dates are PROVISIONAL (derived from airings) until an ERP import supplies " +
            "real values - each row carries 'datesProvisional', and 'renewedAt' has no source yet. To answer " +
            "'how long since customer X renewed', use 'lastAired' (activity recency), not the provisional dates."
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
            val rows = access.data.contractStatus(code, a.bool("byTrader", false))
            buildJsonObject {
                put("code", code)
                put("contractCount", rows.size)
                put("datesAreProvisional", rows.any { it.datesProvisional })
                rows.mapNotNull { it.lastAired }.maxOrNull()?.let { put("lastAired", it) }
                put(
                    "note",
                    "Period dates are provisional (placement-derived) until the Oracle ERP import; " +
                            "renewedAt is not yet sourced - use lastAired for renewal recency.",
                )
                put("contracts", buildJsonArray {
                    rows.forEach { r ->
                        addJsonObject {
                            put("contractNumber", r.contractNumber)
                            put("isGift", r.isGift)
                            r.startDate?.let { put("startDate", it) }
                            r.endDate?.let { put("endDate", it) }
                            r.renewedAt?.let { put("renewedAt", it) }
                            put("datesProvisional", r.datesProvisional)
                            r.firstAired?.let { put("firstAired", it) }
                            r.lastAired?.let { put("lastAired", it) }
                            put("placements", r.placements)
                        }
                    }
                })
            }
        }
}