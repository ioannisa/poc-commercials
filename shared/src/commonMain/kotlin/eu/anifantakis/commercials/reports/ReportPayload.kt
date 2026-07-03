package eu.anifantakis.commercials.reports

import kotlinx.serialization.json.JsonObject

/**
 * Platform-neutral description of one report to generate: which template,
 * its parameter values, and its data rows, all as JSON.
 *
 * This mirrors the wire DTO (`ReportRequest` in :reportcore) but lives in
 * commonMain so screens on every platform - including Android/iOS, which do
 * not link :reportcore - can build one. The `reportsMain` adapter
 * ([toWire]) converts it 1:1 to the wire type on the platforms that can
 * actually generate; the compiler flags any drift between the two.
 *
 * @param reportId   resolves to `reports/{reportId}.jrxml` in :reportcore
 * @param parameters values for the template's `<parameter>` declarations
 * @param rows       one object per detail row; keys match the template's `<field>` names
 */
data class ReportPayload(
    val reportId: String,
    val parameters: JsonObject = JsonObject(emptyMap()),
    val rows: List<JsonObject> = emptyList(),
)
