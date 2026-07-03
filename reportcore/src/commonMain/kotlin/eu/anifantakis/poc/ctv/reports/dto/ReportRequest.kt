package eu.anifantakis.poc.ctv.reports.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Generic wire envelope for every report - the single request type shared by
 * all report producers and consumers. Browser clients (js/wasmJs) serialize
 * it to call the server, the Ktor server deserializes the same class, and the
 * desktop feeds it straight to the engine.
 *
 * There are deliberately no per-report DTOs: the typed data contract of a
 * report is its JRXML template, which declares every parameter and field with
 * a name and a Java class. The engine coerces these JSON values to the
 * declared types at fill time, so adding a report never adds a wire type.
 *
 * @param reportId   resolves to the classpath template `reports/{reportId}.jrxml`
 * @param parameters values for the template's `<parameter>` declarations
 * @param rows       one object per detail row; keys match the template's `<field>` names
 * @param fileName   optional download name hint (used in Content-Disposition)
 */
@Serializable
data class ReportRequest(
    val reportId: String,
    val parameters: JsonObject = JsonObject(emptyMap()),
    val rows: List<JsonObject> = emptyList(),
    val fileName: String? = null,
)
