package eu.anifantakis.poc.ctv.server.routes

import eu.anifantakis.poc.ctv.server.reports.JasperReportGenerator
import eu.anifantakis.poc.ctv.server.reports.ProgramFlowReportRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.reportRoutes() {
    route("/api/reports") {

        // Generate Program Flow PDF report
        post("/program-flow") {
            try {
                val request = call.receive<ProgramFlowReportRequest>()

                val pdfBytes = JasperReportGenerator.generateProgramFlowPdf(request)

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName,
                        request.fileName ?: "program-flow-report.pdf"
                    ).toString()
                )

                call.respondBytes(pdfBytes, ContentType.Application.Pdf)

            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Failed to generate report"))
                )
            }
        }

        // Preview endpoint - returns PDF inline (for browser viewing)
        post("/program-flow/preview") {
            try {
                val request = call.receive<ProgramFlowReportRequest>()

                val pdfBytes = JasperReportGenerator.generateProgramFlowPdf(request)

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Inline.withParameter(
                        ContentDisposition.Parameters.FileName,
                        request.fileName ?: "program-flow-report.pdf"
                    ).toString()
                )

                call.respondBytes(pdfBytes, ContentType.Application.Pdf)

            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Failed to generate report"))
                )
            }
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
