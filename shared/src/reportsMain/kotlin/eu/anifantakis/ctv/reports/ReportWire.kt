package eu.anifantakis.ctv.reports

import eu.anifantakis.ctv.reports.dto.ReportBatchRequest
import eu.anifantakis.ctv.reports.dto.ReportRequest

/**
 * Bridge from the platform-neutral [ReportPayload] (commonMain, buildable on
 * every platform) to the wire/engine DTO (:reportcore, jvm/js/wasmJs only).
 * A field-by-field copy so the compiler flags any drift between the two.
 */
fun ReportPayload.toWire(fileName: String? = null): ReportRequest =
    ReportRequest(
        reportId = reportId,
        parameters = parameters,
        rows = rows,
        fileName = fileName,
    )

fun List<ReportPayload>.toWireBatch(fileName: String? = null): ReportBatchRequest =
    ReportBatchRequest(
        requests = map { it.toWire() },
        fileName = fileName,
    )
