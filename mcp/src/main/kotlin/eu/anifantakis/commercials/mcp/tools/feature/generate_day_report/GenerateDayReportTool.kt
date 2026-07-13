package eu.anifantakis.commercials.mcp.tools.feature.generate_day_report

import eu.anifantakis.commercials.mcp.McpToolException
import eu.anifantakis.commercials.mcp.args
import eu.anifantakis.commercials.mcp.inputSchema
import eu.anifantakis.commercials.mcp.parseIsoDate
import eu.anifantakis.commercials.mcp.prop
import eu.anifantakis.commercials.mcp.runToolBlocks
import eu.anifantakis.commercials.mcp.tools.McpTool
import eu.anifantakis.commercials.mcp.tools.ToolContext
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Generate ONE printable Program Flow PDF for a WHOLE day - every occupied break
 * in air order, as per-break sections - HEADLESSLY via the shared JasperReports
 * engine. The batch counterpart of `generate_break_report`: gather each break's
 * (customer-scoped) spots once from the month cell map, then let
 * [DayReportAssembler] group them by break. Returns a JSON summary plus the PDF
 * as an embedded resource, and saves it server-side.
 */
object GenerateDayReportTool : McpTool {
    override val name = "generate_day_report"
    override val description =
        "Generate the printable Program Flow (ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ) PDF for a whole day - every " +
            "occupied break, in air order, as one document (one section per break). Returns a JSON summary " +
            "plus the PDF as an embedded resource, and also saves it to the server's report output directory. " +
            "date is 'YYYY-MM-DD'. Customer accounts get a day report of only their own spots."
    override val inputSchema = inputSchema(required = listOf("station", "date")) {
        prop("station", "string", "Station id (see list_stations).")
        prop("date", "string", "Date as YYYY-MM-DD.")
    }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult = runToolBlocks(name) {
        val services = ctx.services
        val a = req.args
        val access = services.resolveStation(ctx.caller, a.stringOrNull("station"))
        val date = parseIsoDate(a.string("date"))

        // Load the grid + the month's cell map ONCE, then walk breaks in air order.
        val slots = access.data.loadBreaks().sortedBy { it.hour * 60 + it.minute }
        val (_, byKey) = access.data.loadMonth(date.year, date.monthValue)
        val scoped = services.isCustomerScoped(access.grant)
        val occupied = slots.mapNotNull { slot ->
            var spots = byKey[slot.id to date].orEmpty()
            if (scoped) spots = spots.filter { it.clientCode == access.grant.clientCode }
            if (spots.isEmpty()) null else slot.label to spots
        }

        val request = DayReportAssembler.buildDayReport(date, occupied, services.logoFor(a.string("station")))
        if (request.rows.isEmpty()) {
            throw McpToolException("No spots on $date - nothing to print.")
        }
        val pdf = services.generatePdf(request)
        val saved = services.saveReport(request.fileName ?: "program-flow-day.pdf", pdf)

        val breakCount = request.rows.mapNotNull { it["timeSlot"]?.jsonPrimitive?.content }.distinct().size
        val summary = buildJsonObject {
            put("station", a.string("station"))
            put("date", date.toString())
            put("breakCount", breakCount)
            put("spotCount", request.rows.size)
            put("bytes", pdf.size)
            put("fileName", request.fileName)
            put("savedPath", saved.absolutePath)
        }
        listOf<ContentBlock>(
            TextContent(summary.toString()),
            EmbeddedResource(
                BlobResourceContents(
                    blob = Base64.getEncoder().encodeToString(pdf),
                    uri = saved.toURI().toString(),
                    mimeType = "application/pdf",
                )
            ),
        )
    }
}
