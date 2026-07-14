package eu.anifantakis.commercials.feature.migration_console.presentation.screens.migration

import eu.anifantakis.commercials.core.presentation.helper.UiText
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.auth.SessionRefresher
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.migration_console.domain.BrowseListing
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationFlowMapping
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationMapping
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationRepository
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStart
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStatus
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The server-filesystem picker dialog's sub-state (same screen, same VM).
 * [forSenDir]=true switches the dialog from dump-FILE picking to SEN
 * export-FOLDER picking (navigate in, confirm "use this folder").
 */
@Immutable
data class ServerBrowserState(
    val listing: BrowseListing? = null,
    val error: UiText? = null,
    val forSenDir: Boolean = false,
)

/**
 * What one of the dump's flows should become. Empty [stationId] = do not migrate
 * this flow.
 */
@Immutable
data class FlowTarget(
    val stationId: String = "",
    val stationName: String = "",
)

@Immutable
data class MigrationState(
    val status: MigrationStatus = MigrationStatus(),
    val formError: UiText? = null,
    // step 1: source & target group
    val dumpPath: String = "",
    val senDirPath: String = "",
    val host: String = "localhost",
    val port: String = "3306",
    val username: String = "",
    val password: String = "",
    /** Blank = create a NEW group (below); otherwise the id of a hosted one. */
    val existingGroupId: String = "",
    val groupId: String = "",
    val groupName: String = "",
    val schema: String = "",
    val createSchema: Boolean = true,
    // step 2: one target per detected flow (keyed by forTV)
    val flowTargets: ImmutableMap<Int, FlowTarget> = persistentMapOf(),
    val addToYaml: Boolean = true,
    val browser: ServerBrowserState? = null,
) {
    val running: Boolean get() = status.state in setOf("REPLAYING", "TRANSFORMING")

    /** An existing group owns its schema and credentials; a new one needs both. */
    val isNewGroup: Boolean get() = existingGroupId.isBlank()

    val canStart: Boolean
        get() = dumpPath.isNotBlank() && username.isNotBlank() &&
            if (isNewGroup) groupId.isNotBlank() && schema.isNotBlank() else true

    /** The flows the operator actually filled in - the ones that will migrate. */
    val mappedFlows: List<Pair<Int, FlowTarget>>
        get() = flowTargets.entries
            .filter { it.value.stationId.isNotBlank() }
            .map { it.key to it.value }
            .sortedByDescending { it.first }

    /**
     * At least one flow mapped, each with a name, and no two flows sharing a
     * station id (they are separate stations of the same group).
     */
    val canMap: Boolean
        get() = mappedFlows.isNotEmpty() &&
            mappedFlows.all { (_, t) -> t.stationName.isNotBlank() } &&
            mappedFlows.map { (_, t) -> t.stationId }.toSet().size == mappedFlows.size
}

sealed interface MigrationIntent {
    data class DumpPathChanged(val value: String) : MigrationIntent
    data class SenDirChanged(val value: String) : MigrationIntent
    data class HostChanged(val value: String) : MigrationIntent
    data class PortChanged(val value: String) : MigrationIntent
    data class UsernameChanged(val value: String) : MigrationIntent
    data class PasswordChanged(val value: String) : MigrationIntent
    /** "" = create a new group; otherwise migrate into this hosted one. */
    data class ExistingGroupSelected(val groupId: String) : MigrationIntent
    data class GroupIdChanged(val value: String) : MigrationIntent
    data class GroupNameChanged(val value: String) : MigrationIntent
    data class SchemaChanged(val value: String) : MigrationIntent
    data class CreateSchemaChanged(val value: Boolean) : MigrationIntent
    data object Start : MigrationIntent

    /** Per-flow station target; a blank id skips that flow. */
    data class FlowStationIdChanged(val forTv: Int, val value: String) : MigrationIntent
    data class FlowStationNameChanged(val forTv: Int, val value: String) : MigrationIntent
    data class AddToYamlChanged(val value: Boolean) : MigrationIntent
    data object ChooseMapping : MigrationIntent

    data object Reset : MigrationIntent

    /** [forSenDir]=true opens the picker in folder mode for the SEN exports. */
    data class OpenBrowser(val forSenDir: Boolean = false) : MigrationIntent
    data object CloseBrowser : MigrationIntent
    data class BrowseTo(val path: String?) : MigrationIntent
    data class DumpPicked(val path: String) : MigrationIntent
    data class SenDirPicked(val path: String) : MigrationIntent
}

/**
 * Steers the SERVER-side migration and polls its status live: tighter while a
 * replay/transform runs (700ms), relaxed when idle (2s). Polling runs for as
 * long as the screen subscribes to [state].
 *
 * The wizard mirrors the hosting model: a legacy database is ONE COMPANY's, so
 * it migrates into one GROUP database, and each of its flows (forTV) becomes a
 * station inside that group. The operator therefore does not pick a flow and
 * discard the other - he maps them, and both go in together, sharing the
 * customers and contracts the dump has only one copy of.
 */
@Stable
private const val DONE = "DONE"

class MigrationViewModel(
    private val repository: MigrationRepository,
    private val sessionRefresher: SessionRefresher,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(MigrationState())
    val state by _state
        .onStart { startPolling() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), _state.value)
        .toComposeState(viewModelScope)

    private var polling = false

    fun onAction(intent: MigrationIntent) {
        when (intent) {
            is MigrationIntent.DumpPathChanged -> _state.update { it.copy(dumpPath = intent.value) }
            is MigrationIntent.SenDirChanged -> _state.update { it.copy(senDirPath = intent.value) }
            is MigrationIntent.HostChanged -> _state.update { it.copy(host = intent.value) }
            is MigrationIntent.PortChanged ->
                _state.update { it.copy(port = intent.value.filter { ch -> ch.isDigit() }) }
            is MigrationIntent.UsernameChanged -> _state.update { it.copy(username = intent.value) }
            is MigrationIntent.PasswordChanged -> _state.update { it.copy(password = intent.value) }
            is MigrationIntent.ExistingGroupSelected ->
                _state.update { it.copy(existingGroupId = intent.groupId) }
            is MigrationIntent.GroupIdChanged -> _state.update { it.copy(groupId = intent.value) }
            is MigrationIntent.GroupNameChanged -> _state.update { it.copy(groupName = intent.value) }
            is MigrationIntent.SchemaChanged -> _state.update { it.copy(schema = intent.value) }
            is MigrationIntent.CreateSchemaChanged -> _state.update { it.copy(createSchema = intent.value) }
            MigrationIntent.Start -> start()

            is MigrationIntent.FlowStationIdChanged -> _state.update { s ->
                s.copy(flowTargets = s.flowTargets.updated(intent.forTv) { it.copy(stationId = intent.value) })
            }
            is MigrationIntent.FlowStationNameChanged -> _state.update { s ->
                s.copy(flowTargets = s.flowTargets.updated(intent.forTv) { it.copy(stationName = intent.value) })
            }
            is MigrationIntent.AddToYamlChanged -> _state.update { it.copy(addToYaml = intent.value) }
            MigrationIntent.ChooseMapping -> chooseMapping()

            MigrationIntent.Reset -> viewModelScope.launch {
                when (val result = repository.reset()) {
                    is DataResult.Success -> _state.update {
                        it.copy(status = result.data, flowTargets = persistentMapOf())
                    }
                    is DataResult.Failure -> _state.update {
                        it.copy(formError = result.error.toUiText())
                    }
                }
            }

            is MigrationIntent.OpenBrowser -> {
                _state.update { it.copy(browser = ServerBrowserState(forSenDir = intent.forSenDir)) }
                browseTo(null)
            }
            MigrationIntent.CloseBrowser -> _state.update { it.copy(browser = null) }
            is MigrationIntent.BrowseTo -> browseTo(intent.path)
            is MigrationIntent.DumpPicked ->
                _state.update { it.copy(dumpPath = intent.path, browser = null) }
            is MigrationIntent.SenDirPicked ->
                _state.update { it.copy(senDirPath = intent.path, browser = null) }
        }
    }

    private fun ImmutableMap<Int, FlowTarget>.updated(
        forTv: Int,
        edit: (FlowTarget) -> FlowTarget,
    ): ImmutableMap<Int, FlowTarget> =
        toMutableMap().apply { put(forTv, edit(this[forTv] ?: FlowTarget())) }.toImmutableMap()

    private fun startPolling() {
        if (polling) return
        polling = true
        viewModelScope.launch {
            while (isActive) {
                when (val result = repository.status()) {
                    is DataResult.Success -> {
                        val wasDone = _state.value.status.state == DONE
                        _state.update { it.copy(status = result.data) }
                        // The EDGE, not the state: this poll runs twice a second while
                        // a migration is DONE on screen, and re-syncing the session on
                        // every tick would be a request a second for nothing.
                        if (!wasDone && result.data.state == DONE) onMigrationFinished()
                    }
                    is DataResult.Failure -> Unit   // transient poll miss; keep the last status
                }
                delay(if (_state.value.running) 700 else 2000)
            }
        }
    }

    /**
     * The migration hosted its group LIVE (the server needs no restart), but the
     * client's station list is only re-read on a keep-alive beat - and with a
     * three-day token that beat is six hours out. So the new station sat there,
     * reachable by the API and missing from the dropdown, until the app was
     * restarted (a restart beats immediately). Here we KNOW it just changed.
     */
    private fun onMigrationFinished() {
        viewModelScope.launch { sessionRefresher.refresh() }
    }

    private fun start() {
        val s = _state.value
        if (!s.canStart) return
        _state.update { it.copy(formError = null) }
        viewModelScope.launch {
            val result = repository.start(
                MigrationStart(
                    dumpPath = s.dumpPath.trim(),
                    host = s.host.trim(),
                    port = s.port.toIntOrNull() ?: 3306,
                    username = s.username.trim(),
                    password = s.password,
                    groupId = if (s.isNewGroup) s.groupId.trim() else s.existingGroupId,
                    groupName = s.groupName.trim().ifEmpty { null },
                    schema = s.schema.trim(),
                    createSchema = s.createSchema,
                    senDirPath = s.senDirPath.trim().ifEmpty { null },
                )
            )
            when (result) {
                is DataResult.Success -> _state.update { it.copy(status = result.data) }
                is DataResult.Failure -> _state.update { it.copy(formError = result.error.toUiText()) }
            }
        }
    }

    private fun chooseMapping() {
        val s = _state.value
        if (!s.canMap) return
        _state.update { it.copy(formError = null) }
        viewModelScope.launch {
            val result = repository.chooseMapping(
                MigrationMapping(
                    mappings = s.mappedFlows.map { (forTv, target) ->
                        MigrationFlowMapping(
                            forTv = forTv,
                            stationId = target.stationId.trim(),
                            stationName = target.stationName.trim(),
                        )
                    },
                    addToYaml = s.addToYaml,
                )
            )
            when (result) {
                is DataResult.Success -> _state.update { it.copy(status = result.data) }
                is DataResult.Failure -> _state.update { it.copy(formError = result.error.toUiText()) }
            }
        }
    }

    private fun browseTo(path: String?) {
        viewModelScope.launch {
            when (val result = repository.browse(path)) {
                is DataResult.Success -> _state.update {
                    it.copy(browser = it.browser?.copy(listing = result.data, error = null))
                }
                is DataResult.Failure -> _state.update {
                    it.copy(browser = it.browser?.copy(error = result.error.toUiText()))
                }
            }
        }
    }
}
