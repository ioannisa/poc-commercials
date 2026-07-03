package eu.anifantakis.poc.ctv.reports

import eu.anifantakis.poc.ctv.reports.dto.ReportRequest

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
