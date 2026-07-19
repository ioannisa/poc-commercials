package eu.anifantakis.commercials.feature.galaxy_bridge.domain

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError

/** A hosted group the Galaxy import can target (existing groups only). */
data class GalaxyGroup(val id: String, val name: String, val schema: String)

/** One uploaded Galaxy delivery folder on the server. */
data class GalaxyDelivery(val name: String, val files: Int, val uploadedAtMillis: Long)

/**
 * Where the import is - in numbers the server MEASURED. [total] == 0 means
 * the step has no honest total and the bar must render INDETERMINATE.
 */
data class GalaxyProgress(
    val label: String,
    val done: Long,
    val total: Long,
) {
    /** 0f..1f, or null when there is nothing honest to show. */
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/** One entry of the human review list (never auto-resolved by the importer). */
data class GalaxyReview(val kind: String, val key: String, val detail: String)

/** The importer's counter block - the CLI report, structured. */
data class GalaxySummary(
    val linesTotal: Int = 0,
    val linesCompany: Int = 0,
    val docsSeen: Int = 0,
    val linesNoDocNumber: Int = 0,
    val partiesReferenced: Int = 0,
    val partiesAlreadyStamped: Int = 0,
    val partiesByCode: Int = 0,
    val partiesByVat: Int = 0,
    val partiesInserted: Int = 0,
    val partiesInsertedBare: Int = 0,
    val partiesAmbiguous: Int = 0,
    val partiesConflict: Int = 0,
    val itemsReferenced: Int = 0,
    val itemsAlreadyStamped: Int = 0,
    val itemsStamped: Int = 0,
    val itemsShadowed: Int = 0,
    val itemsInserted: Int = 0,
    val twinDocsSkipped: Int = 0,
    val twinRowsSkipped: Int = 0,
    val untwinned9010Docs: Int = 0,
    val docsExamined: Int = 0,
    val docsAlreadyKeyed: Int = 0,
    val docsMatched: Int = 0,
    val docsInserted: Int = 0,
    val docLinesInserted: Int = 0,
    val docsAmbiguous: Int = 0,
    val docsPayerUnresolved: Int = 0,
    val docsExcludedFromReports: Int = 0,
    val rejectedRecords: Int = 0,
)

data class GalaxyStatus(
    val state: String = "IDLE",
    /** DRY_RUN | APPLY - null before the first run. */
    val mode: String? = null,
    val log: List<String> = emptyList(),
    /** Null when nothing measurable is running. */
    val progress: GalaxyProgress? = null,
    val summary: GalaxySummary? = null,
    val reviews: List<GalaxyReview> = emptyList(),
    val error: String? = null,
    val groups: List<GalaxyGroup> = emptyList(),
    val deliveries: List<GalaxyDelivery> = emptyList(),
    val dictionaryPresent: Boolean = false,
    val groupId: String? = null,
    val companyCode: String? = null,
    val delivery: String? = null,
) {
    val running: Boolean get() = state == "RUNNING"
}

data class GalaxyStart(
    val groupId: String,
    /** Galaxy company: 001 ΚρήτηTV+Radio984, 003 Channel 4, 004 Σητεία. */
    val companyCode: String,
    /** Name of an uploaded delivery. */
    val delivery: String,
    /** false = dry run (writes nothing). */
    val apply: Boolean,
)

enum class GalaxyUploadKind { DELIVERY, DICTIONARY }

/**
 * Super-admin Galaxy (new ERP) import bridge. The import runs ON THE SERVER;
 * deliveries are UPLOADED from the operator's machine as zips (the server may
 * be remote), and the old-export party dictionary is uploaded once and
 * persists server-side. The client only steers and polls.
 */
interface GalaxyBridgeRepository {
    suspend fun status(): DataResult<GalaxyStatus, RemoteError>
    suspend fun start(request: GalaxyStart): DataResult<GalaxyStatus, RemoteError>
    suspend fun reset(): DataResult<GalaxyStatus, RemoteError>

    /** Uploads a zip; [name] labels a DELIVERY (ignored for the dictionary). */
    suspend fun upload(
        kind: GalaxyUploadKind,
        name: String,
        fileName: String,
        bytes: ByteArray,
    ): DataResult<GalaxyStatus, RemoteError>
}
