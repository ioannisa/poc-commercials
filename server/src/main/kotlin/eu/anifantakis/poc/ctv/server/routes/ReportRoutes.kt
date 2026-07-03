package eu.anifantakis.poc.ctv.server.routes

import eu.anifantakis.poc.ctv.reports.dto.ReportRequest
import eu.anifantakis.poc.ctv.reports.engine.ReportEngine
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Generic report endpoints: any report with a template in :reportcore is
 * served here. Adding a report adds no server code - only the template
 * (and its payload builder in :shared).
 */
fun Route.reportRoutes() {
    route("/api/reports") {

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

private suspend fun ApplicationCall.generateReport(disposition: ContentDisposition) {
    try {
        val request = receive<ReportRequest>()

        if (request.reportId != parameters["reportId"]) {
            respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "reportId in path and body do not match")
            )
            return
        }
        if (!ReportEngine.hasTemplate(request.reportId)) {
            respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "Unknown report '${request.reportId}'")
            )
            return
        }

        val pdfBytes = ReportEngine.generatePdf(request)

        response.header(
            HttpHeaders.ContentDisposition,
            disposition.withParameter(
                ContentDisposition.Parameters.FileName,
                request.fileName ?: "${request.reportId}.pdf"
            ).toString()
        )

        respondBytes(pdfBytes, ContentType.Application.Pdf)

    } catch (e: IllegalArgumentException) {
        // Engine validation: unknown parameter/field names, bad value types
        respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to (e.message ?: "Invalid report request"))
        )
    } catch (e: Exception) {
        respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to (e.message ?: "Failed to generate report"))
        )
    }
}
