package eu.anifantakis.commercials.mcp.tools.feature.generate_break_report

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
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Generate the printable Program Flow PDF for one break, HEADLESSLY via the
 * shared JasperReports engine - the Compose app is not involved. Returns a JSON
 * summary plus the PDF as an embedded resource, and saves it server-side.
 */
object GenerateBreakReportTool : McpTool {
    override val name = "generate_break_report"
    override val description =
        "Generate the printable Program Flow (ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ) PDF for one break on one " +
            "date. Returns a JSON summary plus the PDF as an embedded resource, and also saves it to the " +
            "server's report output directory. time is the break label 'HH:mm' (e.g. '17:30'); date is 'YYYY-MM-DD'."
    override val inputSchema = inputSchema(required = listOf("station", "date", "time")) {
        prop("station", "string", "Station id (see list_stations).")
        prop("date", "string", "Date as YYYY-MM-DD.")
        prop("time", "string", "Break time label HH:mm (e.g. 17:30).")
    }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult = runToolBlocks(name) {
        val services = ctx.services
        val a = req.args
        val access = services.resolveStation(ctx.caller, a.stringOrNull("station"))
        val date = parseIsoDate(a.string("date"))
        val time = a.string("time").trim()

        val spots = services.breakSpots(access, date, time)
        if (spots.isEmpty()) {
            throw McpToolException("No spots in break '$time' on $date - nothing to print.")
        }

        val request = BreakReportAssembler.buildBreakReport(date, time, spots, services.logoFor(a.string("station")))
        val pdf = services.generatePdf(request)
        val saved = services.saveReport(request.fileName ?: "program-flow.pdf", pdf)

        val summary = buildJsonObject {
            put("station", a.string("station"))
            put("date", date.toString())
            put("break", time)
            put("spotCount", spots.size)
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
