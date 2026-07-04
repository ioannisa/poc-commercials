package eu.anifantakis.commercials.feature.migration_console.presentation.screens.migration

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.util.toDisplayMessage
import eu.anifantakis.commercials.feature.migration_console.domain.BrowseListing
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationFlowChoice
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationRepository
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStart
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The server-filesystem picker dialog's sub-state (same screen, same VM). */
@Immutable
data class ServerBrowserState(
    val listing: BrowseListing? = null,
    val error: String? = null,
)

@Immutable
data class MigrationState(
    val status: MigrationStatus = MigrationStatus(),
    val formError: String? = null,
    // step 1: source & target
    val dumpPath: String = "",
    val host: String = "localhost",
    val port: String = "3306",
    val username: String = "",
    val password: String = "",
    val schema: String = "",
    val createSchema: Boolean = true,
    // step 2: flow choice
    val selectedFlow: Int? = null,
    val stationId: String = "",
    val stationName: String = "",
    val addToYaml: Boolean = true,
    val browser: ServerBrowserState? = null,
) {
    val running: Boolean get() = status.state in setOf("REPLAYING", "TRANSFORMING")

    val canStart: Boolean get() = dumpPath.isNotBlank() && username.isNotBlank() && schema.isNotBlank()

    val canChooseFlow: Boolean
        get() = selectedFlow != null && (!addToYaml || (stationId.isNotBlank() && stationName.isNotBlank()))
}

sealed interface MigrationIntent {
    data class DumpPathChanged(val value: String) : MigrationIntent
    data class HostChanged(val value: String) : MigrationIntent
    data class PortChanged(val value: String) : MigrationIntent
    data class UsernameChanged(val value: String) : MigrationIntent
    data class PasswordChanged(val value: String) : MigrationIntent
    data class SchemaChanged(val value: String) : MigrationIntent
    data class CreateSchemaChanged(val value: Boolean) : MigrationIntent
    data object Start : MigrationIntent

    data class FlowSelected(val forTv: Int) : MigrationIntent
    data class StationIdChanged(val value: String) : MigrationIntent
    data class StationNameChanged(val value: String) : MigrationIntent
    data class AddToYamlChanged(val value: Boolean) : MigrationIntent
    data object ChooseFlow : MigrationIntent

    data object Reset : MigrationIntent

    data object OpenBrowser : MigrationIntent
    data object CloseBrowser : MigrationIntent
    data class BrowseTo(val path: String?) : MigrationIntent
    data class DumpPicked(val path: String) : MigrationIntent
}

/**
 * Steers the SERVER-side migration and polls its status live: tighter while
 * a replay/transform runs (700ms), relaxed when idle (2s). Polling runs for
 * as long as the screen subscribes to [state].
 */
@Stable
class MigrationViewModel(
    private val repository: MigrationRepository,
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
            is MigrationIntent.HostChanged -> _state.update { it.copy(host = intent.value) }
            is MigrationIntent.PortChanged ->
                _state.update { it.copy(port = intent.value.filter { ch -> ch.isDigit() }) }
            is MigrationIntent.UsernameChanged -> _state.update { it.copy(username = intent.value) }
            is MigrationIntent.PasswordChanged -> _state.update { it.copy(password = intent.value) }
            is MigrationIntent.SchemaChanged -> _state.update { it.copy(schema = intent.value) }
            is MigrationIntent.CreateSchemaChanged -> _state.update { it.copy(createSchema = intent.value) }
            MigrationIntent.Start -> start()

            is MigrationIntent.FlowSelected -> _state.update { it.copy(selectedFlow = intent.forTv) }
            is MigrationIntent.StationIdChanged -> _state.update { it.copy(stationId = intent.value) }
            is MigrationIntent.StationNameChanged -> _state.update { it.copy(stationName = intent.value) }
            is MigrationIntent.AddToYamlChanged -> _state.update { it.copy(addToYaml = intent.value) }
            MigrationIntent.ChooseFlow -> chooseFlow()

            MigrationIntent.Reset -> viewModelScope.launch {
                when (val result = repository.reset()) {
                    is DataResult.Success -> _state.update {
                        it.copy(status = result.data, selectedFlow = null)
                    }
                    is DataResult.Failure -> _state.update {
                        it.copy(formError = result.error.toDisplayMessage())
                    }
                }
            }

            MigrationIntent.OpenBrowser -> {
                _state.update { it.copy(browser = ServerBrowserState()) }
                browseTo(null)
            }
            MigrationIntent.CloseBrowser -> _state.update { it.copy(browser = null) }
            is MigrationIntent.BrowseTo -> browseTo(intent.path)
            is MigrationIntent.DumpPicked ->
                _state.update { it.copy(dumpPath = intent.path, browser = null) }
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        viewModelScope.launch {
            while (isActive) {
                when (val result = repository.status()) {
                    is DataResult.Success -> _state.update { it.copy(status = result.data) }
                    is DataResult.Failure -> Unit   // transient poll miss; keep the last status
                }
                delay(if (_state.value.running) 700 else 2000)
            }
        }
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
                    schema = s.schema.trim(),
                    createSchema = s.createSchema,
                )
            )
            when (result) {
                is DataResult.Success -> _state.update { it.copy(status = result.data) }
                is DataResult.Failure -> _state.update { it.copy(formError = result.error.toDisplayMessage()) }
            }
        }
    }

    private fun chooseFlow() {
        val s = _state.value
        if (!s.canChooseFlow) return
        _state.update { it.copy(formError = null) }
        viewModelScope.launch {
            val result = repository.chooseFlow(
                MigrationFlowChoice(
                    forTv = s.selectedFlow ?: 1,
                    stationId = s.stationId.trim(),
                    stationName = s.stationName.trim(),
                    addToYaml = s.addToYaml,
                )
            )
            when (result) {
                is DataResult.Success -> _state.update { it.copy(status = result.data) }
                is DataResult.Failure -> _state.update { it.copy(formError = result.error.toDisplayMessage()) }
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
                    it.copy(browser = it.browser?.copy(error = result.error.toDisplayMessage()))
                }
            }
        }
    }
}
