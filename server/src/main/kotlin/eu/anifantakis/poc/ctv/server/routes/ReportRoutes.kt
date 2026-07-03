package eu.anifantakis.poc.ctv.server.routes

import eu.anifantakis.poc.ctv.reports.dto.ReportBatchRequest
import eu.anifantakis.poc.ctv.reports.dto.ReportRequest
import eu.anifantakis.poc.ctv.reports.engine.ReportEngine
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generic report endpoints: any report with a template in :reportcore is
 * served here. Adding a report adds no server code - only the template
 * (and its payload builder in :shared).
 */
fun Route.reportRoutes() {
    route("/api/reports") {

        // Generate a batch of reports as ONE PDF, in order (e.g. a month of
        // daily reports). Static segment, so it wins over the {reportId}
        // match below - "batch" is therefore reserved as a report id.
        post("/batch") {
            call.generateBatch(ContentDisposition.Attachment)
        }
        post("/batch/preview") {
            call.generateBatch(ContentDisposition.Inline)
        }

        // Generate a PDF report for download
        post("/{reportId}") {
            call.generateReport(ContentDisposition.Attachment)
        }

        // Preview endpoint - returns PDF inline (for browser viewing)
        post("/{reportId}/preview") {
            call.generateReport(ContentDisposition.Inline)
        }

        // Health check for reports
        get("/status") {
            call.respond(
                mapOf(
                    "status" to "ok",
                    "jasperReports" to "available"
                )
            )
        }
    }
}

// Error handling lives in the StatusPages plugin: malformed bodies
// (BadRequestException from receive) and engine validation failures
// (IllegalArgumentException) map to 400, everything else to 500.

private suspend fun ApplicationCall.generateReport(disposition: ContentDisposition) {
    val request = receive<ReportRequest>()

    if (request.reportId != parameters["reportId"]) {
        respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "reportId in path and body do not match")
        )
        return
    }
    if (respondedUnknownReport(listOf(request))) return

    // Jasper fill + PDF export is blocking work - keep it off Ktor's request threads
    val pdfBytes = withContext(Dispatchers.IO) { ReportEngine.generatePdf(request) }
    respondPdf(pdfBytes, request.fileName ?: "${request.reportId}.pdf", disposition)
}

private suspend fun ApplicationCall.generateBatch(disposition: ContentDisposition) {
    val batch = receive<ReportBatchRequest>()

    if (batch.requests.isEmpty()) {
        respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "The report batch is empty")
        )
        return
    }
    if (respondedUnknownReport(batch.requests)) return

    val pdfBytes = withContext(Dispatchers.IO) { ReportEngine.generatePdf(batch) }
    respondPdf(pdfBytes, batch.fileName ?: "reports.pdf", disposition)
}

/** Responds 404 and returns true if any request targets a missing template. */
private suspend fun ApplicationCall.respondedUnknownReport(requests: List<ReportRequest>): Boolean {
    val unknown = requests.firstOrNull { !ReportEngine.hasTemplate(it.reportId) } ?: return false
    respond(
        HttpStatusCode.NotFound,
        mapOf("error" to "Unknown report '${unknown.reportId}'")
    )
    return true
}

private suspend fun ApplicationCall.respondPdf(
    pdfBytes: ByteArray,
    fileName: String,
    disposition: ContentDisposition
) {
    response.header(
        HttpHeaders.ContentDisposition,
        disposition.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
    )
    respondBytes(pdfBytes, ContentType.Application.Pdf)
}

