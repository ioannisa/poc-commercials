package eu.anifantakis.commercials.feature.user_management.presentation.screens.user_management

import eu.anifantakis.commercials.core.presentation.helper.UiText
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.util.toUiText
import eu.anifantakis.commercials.feature.user_management.domain.ManagedUser
import eu.anifantakis.commercials.feature.user_management.domain.UserGrant
import eu.anifantakis.commercials.feature.user_management.domain.UserManagementRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val NO_ACCESS = "NO ACCESS"

/**
 * Grants selection shared by the create/edit dialogs: stationId -> role
 * name (or NO_ACCESS) plus clientCode for CUSTOMER_VIEWER grants.
 */
@Immutable
data class GrantsSelection(
    val roles: ImmutableMap<String, String> = persistentMapOf(),
    val clientCodes: ImmutableMap<String, String> = persistentMapOf(),
) {
    fun collect(): List<UserGrant> = roles.mapNotNull { (stationId, role) ->
        if (role == NO_ACCESS) null
        else UserGrant(stationId, role, clientCodes[stationId]?.trim()?.takeIf { it.isNotEmpty() })
    }
}

@Immutable
data class CreateUserDialogState(
    val username: String = "",
    val displayName: String = "",
    val password: String = "",
    val grants: GrantsSelection = GrantsSelection(),
    val busy: Boolean = false,
    val error: UiText? = null,
) {
    val canSubmit: Boolean get() = !busy && username.isNotBlank() && displayName.isNotBlank() && password.length >= 6
}

@Immutable
data class ResetPasswordDialogState(
    val user: ManagedUser,
    val password: String = "",
    val busy: Boolean = false,
    val error: UiText? = null,
) {
    val canSubmit: Boolean get() = !busy && password.length >= 6
}

@Immutable
data class EditGrantsDialogState(
    val user: ManagedUser,
    val grants: GrantsSelection,
    val busy: Boolean = false,
    val error: UiText? = null,
)

@Immutable
data class UserManagementState(
    val users: ImmutableList<ManagedUser> = persistentListOf(),
    val message: UiText? = null,
    val create: CreateUserDialogState? = null,
    val reset: ResetPasswordDialogState? = null,
    val editGrants: EditGrantsDialogState? = null,
    val delete: ManagedUser? = null,
)

sealed interface UserManagementIntent {
    data object Reload : UserManagementIntent

    data object ShowCreate : UserManagementIntent
    data class CreateUsernameChanged(val value: String) : UserManagementIntent
    data class CreateDisplayNameChanged(val value: String) : UserManagementIntent
    data class CreatePasswordChanged(val value: String) : UserManagementIntent
    data class CreateGrantRoleChanged(val stationId: String, val role: String) : UserManagementIntent
    data class CreateClientCodeChanged(val stationId: String, val code: String) : UserManagementIntent
    data object ConfirmCreate : UserManagementIntent
    data object DismissCreate : UserManagementIntent

    data class ResetRequested(val user: ManagedUser) : UserManagementIntent
    data class ResetPasswordChanged(val value: String) : UserManagementIntent
    data object ConfirmReset : UserManagementIntent
    data object DismissReset : UserManagementIntent

    data class EditGrantsRequested(val user: ManagedUser) : UserManagementIntent
    data class GrantRoleChanged(val stationId: String, val role: String) : UserManagementIntent
    data class GrantClientCodeChanged(val stationId: String, val code: String) : UserManagementIntent
    data object ConfirmGrants : UserManagementIntent
    data object DismissGrants : UserManagementIntent

    data class DeleteRequested(val user: ManagedUser) : UserManagementIntent
    data object ConfirmDelete : UserManagementIntent
    data object DismissDelete : UserManagementIntent
}

@Stable
class UserManagementViewModel(
    private val repository: UserManagementRepository,
) : BaseGlobalViewModel() {

    private val _state = MutableStateFlow(UserManagementState())
    val state by _state
        .onStart { reload() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), _state.value)
        .toComposeState(viewModelScope)

    fun onAction(intent: UserManagementIntent) {
        when (intent) {
            UserManagementIntent.Reload -> reload()

            UserManagementIntent.ShowCreate -> _state.update { it.copy(create = CreateUserDialogState()) }
            is UserManagementIntent.CreateUsernameChanged ->
                _state.update { it.copy(create = it.create?.copy(username = intent.value)) }
            is UserManagementIntent.CreateDisplayNameChanged ->
                _state.update { it.copy(create = it.create?.copy(displayName = intent.value)) }
            is UserManagementIntent.CreatePasswordChanged ->
                _state.update { it.copy(create = it.create?.copy(password = intent.value)) }
            is UserManagementIntent.CreateGrantRoleChanged -> _state.update {
                it.copy(create = it.create?.copy(grants = it.create.grants.withRole(intent.stationId, intent.role)))
            }
            is UserManagementIntent.CreateClientCodeChanged -> _state.update {
                it.copy(create = it.create?.copy(grants = it.create.grants.withCode(intent.stationId, intent.code)))
            }
            UserManagementIntent.ConfirmCreate -> confirmCreate()
            UserManagementIntent.DismissCreate ->
                _state.update { if (it.create?.busy == true) it else it.copy(create = null) }

            is UserManagementIntent.ResetRequested ->
                _state.update { it.copy(reset = ResetPasswordDialogState(intent.user)) }
            is UserManagementIntent.ResetPasswordChanged ->
                _state.update { it.copy(reset = it.reset?.copy(password = intent.value)) }
            UserManagementIntent.ConfirmReset -> confirmReset()
            UserManagementIntent.DismissReset ->
                _state.update { if (it.reset?.busy == true) it else it.copy(reset = null) }

            is UserManagementIntent.EditGrantsRequested -> _state.update {
                it.copy(
                    editGrants = EditGrantsDialogState(
                        user = intent.user,
                        grants = GrantsSelection(
                            roles = intent.user.grants.associate { g -> g.stationId to g.role }.toImmutableMap(),
                            clientCodes = intent.user.grants
                                .mapNotNull { g -> g.clientCode?.let { c -> g.stationId to c } }
                                .toMap().toImmutableMap(),
                        ),
                    )
                )
            }
            is UserManagementIntent.GrantRoleChanged -> _state.update {
                it.copy(editGrants = it.editGrants?.copy(grants = it.editGrants.grants.withRole(intent.stationId, intent.role)))
            }
            is UserManagementIntent.GrantClientCodeChanged -> _state.update {
                it.copy(editGrants = it.editGrants?.copy(grants = it.editGrants.grants.withCode(intent.stationId, intent.code)))
            }
            UserManagementIntent.ConfirmGrants -> confirmGrants()
            UserManagementIntent.DismissGrants ->
                _state.update { if (it.editGrants?.busy == true) it else it.copy(editGrants = null) }

            is UserManagementIntent.DeleteRequested -> _state.update { it.copy(delete = intent.user) }
            UserManagementIntent.ConfirmDelete -> confirmDelete()
            UserManagementIntent.DismissDelete -> _state.update { it.copy(delete = null) }
        }
    }

    private fun GrantsSelection.withRole(stationId: String, role: String) =
        copy(roles = (roles + (stationId to role)).toImmutableMap())

    private fun GrantsSelection.withCode(stationId: String, code: String) =
        copy(clientCodes = (clientCodes + (stationId to code)).toImmutableMap())

    private fun reload() {
        viewModelScope.launch {
            when (val result = repository.listUsers()) {
                is DataResult.Success -> _state.update { it.copy(users = result.data.toImmutableList()) }
                is DataResult.Failure -> _state.update { it.copy(message = result.error.toUiText()) }
            }
        }
    }

    private fun confirmCreate() {
        val dialog = _state.value.create ?: return
        if (!dialog.canSubmit) return
        _state.update { it.copy(create = dialog.copy(busy = true, error = null)) }
        viewModelScope.launch {
            val result = repository.createUser(
                dialog.username.trim(), dialog.displayName.trim(), dialog.password, dialog.grants.collect(),
            )
            when (result) {
                is DataResult.Success -> {
                    _state.update { it.copy(create = null) }
                    reload()
                }
                is DataResult.Failure -> _state.update {
                    it.copy(create = it.create?.copy(busy = false, error = result.error.toUiText()))
                }
            }
        }
    }

    private fun confirmReset() {
        val dialog = _state.value.reset ?: return
        if (!dialog.canSubmit) return
        _state.update { it.copy(reset = dialog.copy(busy = true, error = null)) }
        viewModelScope.launch {
            when (val result = repository.resetPassword(dialog.user.id, dialog.password)) {
                is DataResult.Success -> {
                    _state.update { it.copy(reset = null) }
                    reload()
                }
                is DataResult.Failure -> _state.update {
                    it.copy(reset = it.reset?.copy(busy = false, error = result.error.toUiText()))
                }
            }
        }
    }

    private fun confirmGrants() {
        val dialog = _state.value.editGrants ?: return
        if (dialog.busy) return
        _state.update { it.copy(editGrants = dialog.copy(busy = true, error = null)) }
        viewModelScope.launch {
            when (val result = repository.setGrants(dialog.user.id, dialog.grants.collect())) {
                is DataResult.Success -> {
                    _state.update { it.copy(editGrants = null) }
                    reload()
                }
                is DataResult.Failure -> _state.update {
                    it.copy(editGrants = it.editGrants?.copy(busy = false, error = result.error.toUiText()))
                }
            }
        }
    }

    private fun confirmDelete() {
        val user = _state.value.delete ?: return
        viewModelScope.launch {
            when (val result = repository.deleteUser(user.id)) {
                is DataResult.Success -> Unit
                is DataResult.Failure -> _state.update { it.copy(message = result.error.toUiText()) }
            }
            _state.update { it.copy(delete = null) }
            reload()
        }
    }
}
