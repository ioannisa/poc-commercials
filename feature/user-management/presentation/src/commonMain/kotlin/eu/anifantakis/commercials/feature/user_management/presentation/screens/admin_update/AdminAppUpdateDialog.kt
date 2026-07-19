package eu.anifantakis.commercials.feature.user_management.presentation.screens.admin_update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.design_system.components.AppDialog
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.user_management.domain.AppUpdateSettings
import eu.anifantakis.commercials.feature.user_management.domain.UserManagementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** Blank is fine (= unset); anything else must be dotted-numeric like 1.2.0. */
private val VERSION_FORMAT = Regex("""\d+(\.\d+){1,3}""")
private fun versionOk(v: String) = v.isBlank() || VERSION_FORMAT.matches(v.trim())

@Immutable
data class AdminAppUpdateState(
    val settings: AppUpdateSettings = AppUpdateSettings(),
    val busy: Boolean = false,
    /** One-shot "Saved" note after a successful publish; any edit clears it. */
    val saved: Boolean = false,
    val error: UiText? = null,
) {
    val latestOk: Boolean get() = versionOk(settings.latest)
    val minSupportedOk: Boolean get() = versionOk(settings.minSupported)
    val canSave: Boolean get() = latestOk && minSupportedOk
}

sealed interface AdminAppUpdateIntent {
    data object Load : AdminAppUpdateIntent
    data class Edited(val settings: AppUpdateSettings) : AdminAppUpdateIntent
    data object Save : AdminAppUpdateIntent
}

@Stable
class AdminAppUpdateViewModel(
    private val repository: UserManagementRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(AdminAppUpdateState())
    val state by _state.toComposeState(viewModelScope)

    fun onAction(intent: AdminAppUpdateIntent) {
        when (intent) {
            AdminAppUpdateIntent.Load -> load()
            is AdminAppUpdateIntent.Edited ->
                _state.update { it.copy(settings = intent.settings, saved = false, error = null) }
            AdminAppUpdateIntent.Save -> save()
        }
    }

    private fun load() {
        viewModelScope.launch {
            when (val result = repository.getAppUpdateSettings()) {
                is DataResult.Success -> _state.update { it.copy(settings = result.data) }
                is DataResult.Failure -> _state.update { it.copy(error = result.error.toUiText()) }
            }
        }
    }

    private fun save() {
        _state.update { it.copy(busy = true, saved = false, error = null) }
        viewModelScope.launch {
            when (val result = repository.setAppUpdateSettings(_state.value.settings)) {
                is DataResult.Success -> _state.update { it.copy(busy = false, saved = true) }
                is DataResult.Failure -> _state.update { it.copy(busy = false, error = result.error.toUiText()) }
            }
        }
    }
}

/**
 * Super-admin publishing of the desktop auto-update advertisement (what the
 * open GET /version serves): latest version, minimum supported version, and
 * one installer URL per package format. Saving is one PUT - no server
 * restart, no server.yaml edit; every desktop client sees the new
 * advertisement at its next startup check.
 */
@Composable
fun AdminAppUpdateDialogRoot(
    onDismiss: () -> Unit,
    viewModel: AdminAppUpdateViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.onAction(AdminAppUpdateIntent.Load) }
    AdminAppUpdateDialog(state = viewModel.state, onIntent = viewModel::onAction, onDismiss = onDismiss)
}

@Composable
private fun AdminAppUpdateDialog(
    state: AdminAppUpdateState,
    onIntent: (AdminAppUpdateIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = state.settings
    fun edit(edited: AppUpdateSettings) = onIntent(AdminAppUpdateIntent.Edited(edited))

    AppDialog(
        title = Strings[StringKey.ADMIN_UPDATE_TITLE],
        onDismiss = onDismiss,
        confirmText = Strings[StringKey.COMMON_SAVE],
        onConfirm = { onIntent(AdminAppUpdateIntent.Save) },
        confirmEnabled = state.canSave,
        confirmBusy = state.busy,
        dismissText = Strings[StringKey.COMMON_CLOSE],
    ) {
        AppText(Strings[StringKey.ADMIN_UPDATE_HINT], AppTextStyle.NOTE)

        AppTextField(
            value = s.latest,
            onValueChange = { edit(s.copy(latest = it)) },
            label = Strings[StringKey.ADMIN_UPDATE_LATEST],
            placeholder = "1.2.0",
            enabled = !state.busy,
            isError = !state.latestOk,
            errorText = Strings[StringKey.ADMIN_UPDATE_VERSION_FORMAT].takeIf { !state.latestOk },
        )
        AppTextField(
            value = s.minSupported,
            onValueChange = { edit(s.copy(minSupported = it)) },
            label = Strings[StringKey.ADMIN_UPDATE_MIN_SUPPORTED],
            placeholder = "1.0.0",
            enabled = !state.busy,
            isError = !state.minSupportedOk,
            errorText = Strings[StringKey.ADMIN_UPDATE_VERSION_FORMAT].takeIf { !state.minSupportedOk },
        )

        // Package-format labels are proper nouns (identical in every language),
        // so they deliberately stay literal instead of becoming StringKeys.
        AppTextField(
            value = s.dmg,
            onValueChange = { edit(s.copy(dmg = it)) },
            label = "macOS (.dmg)",
            placeholder = "/downloads/CommercialsManager2-1.2.0.dmg",
            enabled = !state.busy,
        )
        AppTextField(
            value = s.msi,
            onValueChange = { edit(s.copy(msi = it)) },
            label = "Windows (.msi)",
            placeholder = "/downloads/CommercialsManager2-1.2.0.msi",
            enabled = !state.busy,
        )
        AppTextField(
            value = s.deb,
            onValueChange = { edit(s.copy(deb = it)) },
            label = "Linux (.deb)",
            placeholder = "/downloads/CommercialsManager2-1.2.0.deb",
            enabled = !state.busy,
        )

        if (state.saved) {
            AppText(Strings[StringKey.ADMIN_UPDATE_SAVED], AppTextStyle.NOTE)
        }
        state.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }
    }
}
