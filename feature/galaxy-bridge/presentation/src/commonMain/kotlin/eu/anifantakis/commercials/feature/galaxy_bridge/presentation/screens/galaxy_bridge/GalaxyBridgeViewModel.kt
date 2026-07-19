package eu.anifantakis.commercials.feature.galaxy_bridge.presentation.screens.galaxy_bridge

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyBridgeRepository
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyReview
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyStart
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyStatus
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyUploadKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class GalaxyBridgeState(
    val status: GalaxyStatus = GalaxyStatus(),
    val formError: UiText? = null,
    /** Target group id - blank until the operator picks one. */
    val selectedGroupId: String = "",
    /** Galaxy company code (001 ΚρήτηTV+R984 / 003 Channel 4 / 004 Σητεία). */
    val selectedCompany: String = "001",
    /** Name of the uploaded delivery to import - blank until picked. */
    val selectedDelivery: String = "",
    val uploadingDelivery: Boolean = false,
    val uploadingDictionary: Boolean = false,
    /** The APPLY confirmation dialog - writes to a live group database. */
    val confirmApply: Boolean = false,
    /** Review-list filter: a review kind, or null = all. */
    val reviewFilter: String? = null,
) {
    val running: Boolean get() = status.running

    val canRun: Boolean
        get() = !running && !uploadingDelivery && !uploadingDictionary &&
            selectedGroupId.isNotBlank() && selectedDelivery.isNotBlank()

    /** kind → count, largest first - drives the review filter chips. */
    val reviewKinds: List<Pair<String, Int>>
        get() = status.reviews.groupingBy { it.kind }.eachCount()
            .toList().sortedByDescending { it.second }

    val filteredReviews: List<GalaxyReview>
        get() = reviewFilter?.let { f -> status.reviews.filter { it.kind == f } } ?: status.reviews
}

sealed interface GalaxyBridgeIntent {
    data class GroupSelected(val groupId: String) : GalaxyBridgeIntent
    data class CompanySelected(val code: String) : GalaxyBridgeIntent
    data class DeliverySelected(val name: String) : GalaxyBridgeIntent

    /** A zip picked on the OPERATOR's machine; [name] labels the delivery. */
    data class UploadDelivery(val name: String, val fileName: String, val bytes: ByteArray) : GalaxyBridgeIntent
    data class UploadDictionary(val fileName: String, val bytes: ByteArray) : GalaxyBridgeIntent

    data object RunDryRun : GalaxyBridgeIntent
    data object AskApply : GalaxyBridgeIntent
    data object ConfirmApply : GalaxyBridgeIntent
    data object DismissApply : GalaxyBridgeIntent
    data object Reset : GalaxyBridgeIntent
    data class ReviewFilterChanged(val kind: String?) : GalaxyBridgeIntent
}

/**
 * Steers the SERVER-side Galaxy import and polls its status live: tighter
 * while a run is in flight (700ms), relaxed when idle (2s). Polling runs for
 * as long as the screen subscribes to [state].
 *
 * The flow mirrors the CLI it wraps: upload a delivery zip once, DRY-RUN as
 * many times as needed (writes nothing, produces the full summary + review
 * list), then APPLY - which is idempotent, so re-applying a delivery is a
 * zero-write re-run, never a duplicate import.
 */
class GalaxyBridgeViewModel(
    private val repository: GalaxyBridgeRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(GalaxyBridgeState())
    val state by _state
        .onStart { startPolling() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), _state.value)
        .toComposeState(viewModelScope)

    private var polling = false

    fun onAction(intent: GalaxyBridgeIntent) {
        when (intent) {
            is GalaxyBridgeIntent.GroupSelected -> _state.update { it.copy(selectedGroupId = intent.groupId) }
            is GalaxyBridgeIntent.CompanySelected -> _state.update { it.copy(selectedCompany = intent.code) }
            is GalaxyBridgeIntent.DeliverySelected -> _state.update { it.copy(selectedDelivery = intent.name) }

            is GalaxyBridgeIntent.UploadDelivery ->
                upload(GalaxyUploadKind.DELIVERY, intent.name, intent.fileName, intent.bytes)
            is GalaxyBridgeIntent.UploadDictionary ->
                upload(GalaxyUploadKind.DICTIONARY, "", intent.fileName, intent.bytes)

            GalaxyBridgeIntent.RunDryRun -> start(apply = false)
            GalaxyBridgeIntent.AskApply -> _state.update { it.copy(confirmApply = true) }
            GalaxyBridgeIntent.ConfirmApply -> {
                _state.update { it.copy(confirmApply = false) }
                start(apply = true)
            }
            GalaxyBridgeIntent.DismissApply -> _state.update { it.copy(confirmApply = false) }

            GalaxyBridgeIntent.Reset -> viewModelScope.launch {
                when (val result = repository.reset()) {
                    is DataResult.Success -> onStatus(result.data)
                    is DataResult.Failure -> _state.update { it.copy(formError = result.error.toUiText()) }
                }
            }

            is GalaxyBridgeIntent.ReviewFilterChanged -> _state.update { it.copy(reviewFilter = intent.kind) }
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        viewModelScope.launch {
            while (isActive) {
                when (val result = repository.status()) {
                    is DataResult.Success -> onStatus(result.data)
                    is DataResult.Failure -> Unit   // transient poll miss; keep the last status
                }
                delay(if (_state.value.running) 700 else 2000)
            }
        }
    }

    /**
     * Applies a fresh status and auto-fills the harmless selections: the
     * NEWEST delivery when none is picked, and the group only when there is
     * exactly one hosted (a database target is never guessed among many).
     */
    private fun onStatus(status: GalaxyStatus) {
        _state.update { s ->
            // A selection that no longer exists on the server (deleted or
            // renamed delivery) must never reach /start - drop it, then fall
            // back to the newest available.
            val validated = s.selectedDelivery
                .takeIf { name -> status.deliveries.any { it.name == name } } ?: ""
            val delivery = validated.ifBlank { status.deliveries.firstOrNull()?.name ?: "" }
            val group = s.selectedGroupId.ifBlank {
                status.groups.singleOrNull()?.id ?: ""
            }
            s.copy(status = status, selectedDelivery = delivery, selectedGroupId = group)
        }
    }

    private fun upload(kind: GalaxyUploadKind, name: String, fileName: String, bytes: ByteArray) {
        _state.update {
            it.copy(
                formError = null,
                uploadingDelivery = it.uploadingDelivery || kind == GalaxyUploadKind.DELIVERY,
                uploadingDictionary = it.uploadingDictionary || kind == GalaxyUploadKind.DICTIONARY,
            )
        }
        viewModelScope.launch {
            val result = repository.upload(kind, name, fileName, bytes)
            _state.update {
                it.copy(
                    uploadingDelivery = if (kind == GalaxyUploadKind.DELIVERY) false else it.uploadingDelivery,
                    uploadingDictionary = if (kind == GalaxyUploadKind.DICTIONARY) false else it.uploadingDictionary,
                )
            }
            when (result) {
                is DataResult.Success -> {
                    onStatus(result.data)
                    if (kind == GalaxyUploadKind.DELIVERY) {
                        _state.update { it.copy(selectedDelivery = name) }
                    }
                }
                is DataResult.Failure -> _state.update { it.copy(formError = result.error.toUiText()) }
            }
        }
    }

    private fun start(apply: Boolean) {
        val s = _state.value
        if (!s.canRun) return
        _state.update { it.copy(formError = null) }
        viewModelScope.launch {
            val result = repository.start(
                GalaxyStart(
                    groupId = s.selectedGroupId,
                    companyCode = s.selectedCompany,
                    delivery = s.selectedDelivery,
                    apply = apply,
                )
            )
            when (result) {
                is DataResult.Success -> onStatus(result.data)
                is DataResult.Failure -> _state.update { it.copy(formError = result.error.toUiText()) }
            }
        }
    }
}
