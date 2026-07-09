package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mcp.args
import eu.anifantakis.commercials.mcp.inputSchema
import eu.anifantakis.commercials.mcp.parseIsoDate
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

/** The spots scheduled in a specific break on a specific date, in air order. */
object SpotsInBreakTool : McpTool {
    override val name = "spots_in_break"
    override val description =
        "The spots scheduled in a specific break on a specific date, in air order. " +
            "time is the break label 'HH:mm' (e.g. '17:30'); date is 'YYYY-MM-DD'."
    override val inputSchema = inputSchema(required = listOf("station", "date", "time")) {
        prop("station", "string", "Station id.")
        prop("date", "string", "Date as YYYY-MM-DD.")
        prop("time", "string", "Break time label HH:mm (e.g. 17:30).")
    }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult =
        runTool(name) {
            val services = ctx.services
            val a = req.args
            val access = services.resolveStation(ctx.caller, a.stringOrNull("station"))
            val date = parseIsoDate(a.string("date"))
            val time = a.string("time").trim()
            val spots = services.breakSpots(access, date, time)
            buildJsonObject {
                put("date", date.toString())
                put("break", time)
                put("spotCount", spots.size)
                put("totalDurationSeconds", spots.sumOf { it.durationSeconds })
                put("spots", buildJsonArray {
                    spots.forEach { s ->
                        addJsonObject {
                            put("position", s.position)
                            put("clientCode", s.clientCode)
                            put("clientName", s.clientName)
                            put("message", s.message)
                            put("durationSeconds", s.durationSeconds)
                            put("type", s.type)
                            s.salesItem?.let { put("salesItem", it) }
                            put("contract", s.contract)
                            put("isGift", s.isGift)
                            put("flow", s.flow)
                            s.programName?.let { put("programName", it) }
                        }
                    }
                })
            }
        }
}