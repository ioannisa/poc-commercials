package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.reports.dto.ReportBatchRequest
import eu.anifantakis.commercials.reports.dto.ReportRequest
import eu.anifantakis.commercials.reports.engine.ReportEngine
import eu.anifantakis.commercials.server.plugins.authUser
import eu.anifantakis.commercials.server.plugins.grantedStationIdOrRespond
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Generic report endpoints: any report with a template in :reportcore is
 * served here. Adding a report adds no server code - only the template
 * (and its payload builder in :shared).
 */
fun Route.reportRoutes(registry: StationRegistry) {
    route("/api/reports") {

        /**
         * Generate a batch of reports as one downloadable PDF, in order (e.g. a month of daily reports).
         *
         * Static segment, so it wins over the {reportId} match below - "batch"
         * is therefore reserved as a report id.
         *
         * Tag: Reports
         */
        post("/batch") {
            call.generateBatch(registry, ContentDisposition.Attachment)
        }
        /**
         * Generate a batch of reports as one PDF and return it inline for in-browser preview.
         *
         * Tag: Reports
         */
        post("/batch/preview") {
            call.generateBatch(registry, ContentDisposition.Inline)
        }

        /**
         * Generate a PDF for the given report id and return it as a file download.
         *
         * Tag: Reports
         */
        post("/{reportId}") {
            call.generateReport(registry, ContentDisposition.Attachment)
        }

        /**
         * Generate a PDF for the given report id and return it inline for browser preview.
         *
         * Tag: Reports
         */
        post("/{reportId}/preview") {
            call.generateReport(registry, ContentDisposition.Inline)
        }

        /**
         * Return the granted station's report logo as image bytes, or 404 when it has none.
         *
         * server.yaml holds a PATH, and a path is meaningless to a client on
         * another machine - which the desktop app usually is, and it renders its
         * reports IN-PROCESS. So the path never leaves the server: the image
         * does. The desktop caches these bytes to a file of its own and hands
         * Jasper that (see StationLogoCache).
         *
         * 404 for a station with no logo, or one whose configured file has gone
         * missing - the caller then prints the placeholder. It is a report logo,
         * not an error condition.
         *
         * Tag: Reports
         */
        get("/logo") {
            // A token is not enough: this must be a station the caller is
            // granted. (403/400 answered inside.)
            val stationId = call.grantedStationIdOrRespond() ?: return@get
            val file = registry.config(stationId)?.logo
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { it.isFile && it.canRead() }

            if (file == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No logo for this station"))
                return@get
            }
            // The caller only re-downloads when the file actually changed.
            call.response.header(HttpHeaders.ETag, "\"${file.lastModified()}-${file.length()}\"")
            call.respondBytes(
                withContext(Dispatchers.IO) { file.readBytes() },
                contentTypeOf(file),
            )
        }

        /**
         * Report reports subsystem health, confirming JasperReports is available.
         *
         * Tag: Reports
         */
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

private suspend fun ApplicationCall.generateReport(
    registry: StationRegistry,
    disposition: ContentDisposition,
) {
    val request = receive<ReportRequest>().withServerLogo(registry, this)

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

private suspend fun ApplicationCall.generateBatch(
    registry: StationRegistry,
    disposition: ContentDisposition,
) {
    val received = receive<ReportBatchRequest>()
    val batch = received.copy(requests = received.requests.map { it.withServerLogo(registry, this) })

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

/**
 * THE LOGO IS THE SERVER'S TO DECIDE, NOT THE CALLER'S.
 *
 * Every parameter in a [ReportRequest] is client-supplied, and Jasper turns
 * `LOGO_PATH` into a file read. Honouring the caller's value would hand any
 * logged-in user a file-read primitive on the server host - so whatever arrives
 * is DISCARDED, and the path is re-derived from this station's `server.yaml`
 * entry (null when it has no logo, which the template renders as a placeholder).
 *
 * Every request already carries `?station=` (ApiHttpClient stamps it on every
 * call), and the logo is only injected for a station the caller is actually
 * GRANTED - a token alone will not paint another station's brand on your report.
 * No station, or no grant: no logo, and the report still renders.
 *
 * (The desktop never comes through here at all: it renders in-process, and gets
 * its logo as BYTES from GET /api/reports/logo, which it caches to a file of its
 * own. Same server.yaml entry, two readers - and the path stays server-side.)
 */
private fun ReportRequest.withServerLogo(registry: StationRegistry, call: ApplicationCall): ReportRequest {
    // Only templates that declare LOGO_PATH get one - never inject a parameter
    // a template does not have.
    if (!parameters.containsKey(LOGO_PATH)) return this

    val logo = call.request.queryParameters["station"]
        ?.takeIf { call.authUser().grantFor(it) != null }
        ?.let { registry.config(it)?.logo }
        ?.takeIf { it.isNotBlank() }

    return copy(
        parameters = JsonObject(
            parameters + (LOGO_PATH to (logo?.let(::JsonPrimitive) ?: JsonNull))
        )
    )
}

private const val LOGO_PATH = "LOGO_PATH"

/** Jasper loads the image by content, so this only has to be honest, not clever. */
private fun contentTypeOf(file: File): ContentType = when (file.extension.lowercase()) {
    "png" -> ContentType.Image.PNG
    "jpg", "jpeg" -> ContentType.Image.JPEG
    "gif" -> ContentType.Image.GIF
    "svg" -> ContentType("image", "svg+xml")
    else -> ContentType.Application.OctetStream
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

