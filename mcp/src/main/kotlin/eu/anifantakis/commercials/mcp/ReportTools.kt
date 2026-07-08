package eu.anifantakis.commercials.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Registers the report-generation tools. Reports are produced HEADLESSLY on the
 * backend via the shared JasperReports engine - the Compose app is not involved.
 */
internal fun Server.registerReportTools(caller: McpCaller, services: McpToolServices) {

    addTool(
        name = "generate_break_report",
        description = "Generate the printable Program Flow (ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ) PDF for one break on one " +
            "date. Returns a JSON summary plus the PDF as an embedded resource, and also saves it to the " +
            "server's report output directory. time is the break label 'HH:mm' (e.g. '17:30'); date is 'YYYY-MM-DD'.",
        inputSchema = inputSchema(required = listOf("station", "date", "time")) {
            prop("station", "string", "Station id (see list_stations).")
            prop("date", "string", "Date as YYYY-MM-DD.")
            prop("time", "string", "Break time label HH:mm (e.g. 17:30).")
        },
    ) { req ->
        runToolBlocks("generate_break_report") {
            val a = req.args
            val access = services.resolveStation(caller, a.stringOrNull("station"))
            val date = parseIsoDate(a.string("date"))
            val time = a.string("time").trim()

            val spots = services.breakSpots(access, date, time)
            if (spots.isEmpty()) {
                throw McpToolException("No spots in break '$time' on $date - nothing to print.")
            }

            val request = BreakReportAssembler.buildBreakReport(date, time, spots)
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
}
