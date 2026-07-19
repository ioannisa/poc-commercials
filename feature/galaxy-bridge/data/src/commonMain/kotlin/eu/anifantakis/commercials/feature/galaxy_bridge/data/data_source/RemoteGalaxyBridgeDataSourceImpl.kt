package eu.anifantakis.commercials.feature.galaxy_bridge.data.data_source

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.domain.util.map
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyDelivery
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyGroup
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyProgress
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyReview
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyStart
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyStatus
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxySummary
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyUploadKind
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.data_source.RemoteGalaxyBridgeDataSource
import kotlinx.serialization.Serializable

@Serializable
private data class StartDto(
    val groupId: String,
    val companyCode: String,
    val delivery: String,
    val apply: Boolean = false,
)

@Serializable
private data class GroupDto(val id: String, val name: String, val schema: String)

@Serializable
private data class DeliveryDto(val name: String, val files: Int, val uploadedAtMillis: Long)

@Serializable
private data class ProgressDto(val label: String, val done: Long, val total: Long)

@Serializable
private data class ReviewDto(val kind: String, val key: String, val detail: String)

@Serializable
private data class SummaryDto(
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

@Serializable
private data class StatusDto(
    val state: String = "IDLE",
    val mode: String? = null,
    val log: List<String> = emptyList(),
    val progress: ProgressDto? = null,
    val summary: SummaryDto? = null,
    val reviews: List<ReviewDto> = emptyList(),
    val error: String? = null,
    val groups: List<GroupDto> = emptyList(),
    val deliveries: List<DeliveryDto> = emptyList(),
    val dictionaryPresent: Boolean = false,
    val groupId: String? = null,
    val companyCode: String? = null,
    val delivery: String? = null,
)

private fun SummaryDto.toDomain() = GalaxySummary(
    linesTotal, linesCompany, docsSeen, linesNoDocNumber,
    partiesReferenced, partiesAlreadyStamped, partiesByCode, partiesByVat,
    partiesInserted, partiesInsertedBare, partiesAmbiguous, partiesConflict,
    itemsReferenced, itemsAlreadyStamped, itemsStamped, itemsShadowed, itemsInserted,
    twinDocsSkipped, twinRowsSkipped, untwinned9010Docs,
    docsExamined, docsAlreadyKeyed, docsMatched, docsInserted, docLinesInserted,
    docsAmbiguous, docsPayerUnresolved, docsExcludedFromReports,
    rejectedRecords,
)

private fun StatusDto.toDomain() = GalaxyStatus(
    state = state,
    mode = mode,
    log = log,
    progress = progress?.let { GalaxyProgress(it.label, it.done, it.total) },
    summary = summary?.toDomain(),
    reviews = reviews.map { GalaxyReview(it.kind, it.key, it.detail) },
    error = error,
    groups = groups.map { GalaxyGroup(it.id, it.name, it.schema) },
    deliveries = deliveries.map { GalaxyDelivery(it.name, it.files, it.uploadedAtMillis) },
    dictionaryPresent = dictionaryPresent,
    groupId = groupId,
    companyCode = companyCode,
    delivery = delivery,
)

class RemoteGalaxyBridgeDataSourceImpl(private val api: ApiHttpClient) : RemoteGalaxyBridgeDataSource {

    override suspend fun status(): DataResult<GalaxyStatus, RemoteError> =
        api.getRemote<StatusDto>("/api/admin/galaxy/status").map { it.toDomain() }

    override suspend fun start(request: GalaxyStart): DataResult<GalaxyStatus, RemoteError> =
        api.postRemote<StartDto, StatusDto>(
            "/api/admin/galaxy/start",
            StartDto(request.groupId, request.companyCode, request.delivery, request.apply),
        ).map { it.toDomain() }

    override suspend fun reset(): DataResult<GalaxyStatus, RemoteError> =
        api.postRemote<StatusDto, StatusDto>("/api/admin/galaxy/reset").map { it.toDomain() }

    override suspend fun upload(
        kind: GalaxyUploadKind,
        name: String,
        fileName: String,
        bytes: ByteArray,
    ): DataResult<GalaxyStatus, RemoteError> =
        api.postFileRemote<StatusDto>(
            "/api/admin/galaxy/upload",
            fileName,
            bytes,
            "application/zip",
            "kind" to if (kind == GalaxyUploadKind.DELIVERY) "delivery" else "dictionary",
            "name" to name.takeIf { it.isNotBlank() },
        ).map { it.toDomain() }
}
