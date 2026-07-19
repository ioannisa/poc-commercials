package eu.anifantakis.commercials.galaxy

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class GalaxyStartDto(
    val groupId: String,
    /** Galaxy company: 001 ΙΚΑΡΟΣ (crete), 003 Channel 4, 004 Σητεία. */
    val companyCode: String = "001",
    /** Name of a previously uploaded delivery. */
    val delivery: String,
    /** false = dry run (default, writes nothing). */
    val apply: Boolean = false,
)

/** A hosted group the import can target. */
@Serializable
data class GalaxyGroupDto(val id: String, val name: String, val schema: String)

@Serializable
data class GalaxyDeliveryDto(val name: String, val files: Int, val uploadedAtMillis: Long)

/** Same honesty contract as the migration bar: total 0 ⇒ indeterminate. */
@Serializable
data class GalaxyProgressDto(val label: String, val done: Long, val total: Long)

@Serializable
data class GalaxyReviewDto(val kind: String, val key: String, val detail: String)

/** Mirrors [GalaxyImporter.Summary] - the CLI report's counter block. */
@Serializable
data class GalaxySummaryDto(
    val linesTotal: Int,
    val linesCompany: Int,
    val docsSeen: Int,
    val linesNoDocNumber: Int,
    val partiesReferenced: Int,
    val partiesAlreadyStamped: Int,
    val partiesByCode: Int,
    val partiesByVat: Int,
    val partiesInserted: Int,
    val partiesInsertedBare: Int,
    val partiesAmbiguous: Int,
    val partiesConflict: Int,
    val itemsReferenced: Int,
    val itemsAlreadyStamped: Int,
    val itemsStamped: Int,
    val itemsShadowed: Int,
    val itemsInserted: Int,
    val twinDocsSkipped: Int,
    val twinRowsSkipped: Int,
    val untwinned9010Docs: Int,
    val docsExamined: Int,
    val docsAlreadyKeyed: Int,
    val docsMatched: Int,
    val docsInserted: Int,
    val docLinesInserted: Int,
    val docsAmbiguous: Int,
    val docsPayerUnresolved: Int,
    val docsExcludedFromReports: Int,
    val rejectedRecords: Int,
)

@Serializable
data class GalaxyStatusDto(
    val state: String,
    /** DRY_RUN | APPLY - null before the first run. */
    val mode: String? = null,
    val log: List<String> = emptyList(),
    val progress: GalaxyProgressDto? = null,
    val summary: GalaxySummaryDto? = null,
    val reviews: List<GalaxyReviewDto> = emptyList(),
    val error: String? = null,
    val groups: List<GalaxyGroupDto> = emptyList(),
    val deliveries: List<GalaxyDeliveryDto> = emptyList(),
    val dictionaryPresent: Boolean = false,
    val groupId: String? = null,
    val companyCode: String? = null,
    val delivery: String? = null,
)

/**
 * Drives the Galaxy (new ERP) import from the in-app Galaxy Bridge screen.
 * Super administrator only; the heavy lifting happens server-side in
 * [GalaxyImportService] - deliveries are UPLOADED from the operator's
 * machine, so a remote server needs no filesystem access from the client.
 * Validation errors surface as 400 via the host's StatusPages.
 *
 * [requireAdmin] is the HOST's guard: security stays the server's concern -
 * this module only demands that every endpoint pass it.
 */
fun Route.galaxyRoutes(
    service: GalaxyImportService,
    requireAdmin: suspend ApplicationCall.() -> Boolean,
) {
    route("/api/admin/galaxy") {

        /**
         * Return the Galaxy import state, log, progress, summary, review list, target groups and uploaded deliveries.
         *
         * Tag: Galaxy
         */
        get("/status") {
            if (!call.requireAdmin()) return@get
            call.respond(service.snapshot().toDto())
        }

        /**
         * Start a Galaxy import run (dry run unless apply=true) for a group, company and uploaded delivery.
         *
         * Tag: Galaxy
         */
        post("/start") {
            if (!call.requireAdmin()) return@post
            val req = call.receive<GalaxyStartDto>()
            withContext(Dispatchers.IO) {
                service.start(
                    GalaxyImportService.StartRequest(
                        groupId = req.groupId.trim(),
                        companyCode = req.companyCode.trim(),
                        delivery = req.delivery.trim(),
                        apply = req.apply,
                    )
                )
            }
            call.respond(service.snapshot().toDto())
        }

        /**
         * Reset the Galaxy import back to its idle initial state.
         *
         * Tag: Galaxy
         */
        post("/reset") {
            if (!call.requireAdmin()) return@post
            service.reset()
            call.respond(service.snapshot().toDto())
        }

        /**
         * Upload a zipped Galaxy delivery (kind=delivery&name=...) or the old-export party dictionary (kind=dictionary).
         *
         * The zip is expanded server-side into the galaxy-imports folder;
         * a single all-enclosing root folder is stripped automatically.
         *
         * Tag: Galaxy
         */
        post("/upload") {
            if (!call.requireAdmin()) return@post
            val kind = when (call.request.queryParameters["kind"]) {
                "delivery" -> GalaxyImportService.UploadKind.DELIVERY
                "dictionary" -> GalaxyImportService.UploadKind.DICTIONARY
                else -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "kind must be delivery or dictionary"))
                    return@post
                }
            }
            val name = call.request.queryParameters["name"]?.trim().orEmpty()
            if (kind == GalaxyImportService.UploadKind.DELIVERY && name.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A delivery upload needs a name"))
                return@post
            }
            var stored = false
            val multipart = call.receiveMultipart()
            multipart.forEachPart { part ->
                if (part is PartData.FileItem && !stored) {
                    withContext(Dispatchers.IO) {
                        part.provider().toInputStream().use { zip ->
                            service.saveUpload(kind, name, zip)
                        }
                    }
                    stored = true
                }
                part.release()
            }
            if (!stored) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file part in the upload"))
                return@post
            }
            call.respond(service.snapshot().toDto())
        }

        /**
         * Download the last run's review list as a semicolon CSV (UTF-8 BOM for Excel).
         *
         * Tag: Galaxy
         */
        get("/review.csv") {
            if (!call.requireAdmin()) return@get
            val reviews = service.snapshot().summary?.reviews.orEmpty()
            val csv = buildString {
                append('\uFEFF').append("kind;key;detail\n")
                reviews.forEach { r ->
                    append(
                        listOf(r.kind, r.key, r.detail)
                            .joinToString(";") { it.replace(';', ',').replace('\n', ' ') }
                    ).append('\n')
                }
            }
            call.respondText(csv, ContentType.Text.CSV.withParameter("charset", "utf-8"))
        }
    }
}

private fun GalaxyImportService.Snapshot.toDto() = GalaxyStatusDto(
    state = state.name,
    mode = mode?.name,
    log = log,
    progress = progress?.let { GalaxyProgressDto(it.label, it.done, it.total) },
    summary = summary?.let { s ->
        GalaxySummaryDto(
            s.linesTotal, s.linesCompany, s.docsSeen, s.linesNoDocNumber,
            s.partiesReferenced, s.partiesAlreadyStamped, s.partiesByCode, s.partiesByVat,
            s.partiesInserted, s.partiesInsertedBare, s.partiesAmbiguous, s.partiesConflict,
            s.itemsReferenced, s.itemsAlreadyStamped, s.itemsStamped, s.itemsShadowed, s.itemsInserted,
            s.twinDocsSkipped, s.twinRowsSkipped, s.untwinned9010Docs,
            s.docsExamined, s.docsAlreadyKeyed, s.docsMatched, s.docsInserted, s.docLinesInserted,
            s.docsAmbiguous, s.docsPayerUnresolved, s.docsExcludedFromReports,
            s.rejectedRecords,
        )
    },
    reviews = summary?.reviews.orEmpty().map { GalaxyReviewDto(it.kind, it.key, it.detail) },
    error = error,
    groups = groups.map { GalaxyGroupDto(it.id, it.name, it.schema) },
    deliveries = deliveries.map { GalaxyDeliveryDto(it.name, it.files, it.uploadedAtMillis) },
    dictionaryPresent = dictionaryPresent,
    groupId = groupId,
    companyCode = companyCode,
    delivery = delivery,
)
