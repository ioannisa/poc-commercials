package eu.anifantakis.commercials.feature.user_management.presentation.screens.user_management

import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.util.toStringKey
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.domain.auth.AppRole
import org.koin.compose.viewmodel.koinViewModel

/**
 * Super-admin user management: list, create, reset password, edit per-station
 * grants, delete. The station list comes from the admin's own session (the
 * super admin has implicit access to every hosted station) and is passed in
 * by the app layer as [stationChoices] (id to name).
 */
@Composable
fun UserManagementScreenRoot(
    stationChoices: List<Pair<String, String>>,
    onBack: () -> Unit,
    viewModel: UserManagementViewModel = koinViewModel(),
) {
    UserManagementScreen(
        state = viewModel.state,
        stationChoices = stationChoices,
        onIntent = viewModel::onAction,
        onNavIntent = { navIntent ->
            when (navIntent) {
                UserManagementScreenNavIntent.OnBack -> onBack()
            }
        },
    )
}

/**
 * Navigation-only actions of this screen — ALWAYS routed through this single
 * parameter (a predictable shape you can expect on every screen, ready to
 * accept more nav without a refactor). Not a ViewModel [UserManagementIntent].
 */
private sealed interface UserManagementScreenNavIntent {
    data object OnBack : UserManagementScreenNavIntent
}

@Composable
private fun UserManagementScreen(
    state: UserManagementState,
    stationChoices: List<Pair<String, String>>,
    onIntent: (UserManagementIntent) -> Unit,
    onNavIntent: (UserManagementScreenNavIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onNavIntent(UserManagementScreenNavIntent.OnBack) }) {
                Icon(AppIcons.arrowBack, contentDescription = Strings[StringKey.COMMON_BACK])
            }
            AppText(Strings[StringKey.USER_MGMT_TITLE], AppTextStyle.SCREEN_TITLE)
            Spacer(Modifier.weight(1f))
            Button(onClick = { onIntent(UserManagementIntent.ShowCreate) }) {
                Icon(AppIcons.add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                AppText(Strings[StringKey.USER_MGMT_NEW_USER], AppTextStyle.BUTTON)
            }
        }

        state.message?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.users, key = { it.id }) { user ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppText(user.username, AppTextStyle.ITEM_TITLE)
                                Spacer(Modifier.width(8.dp))
                                AppText(user.displayName, AppTextStyle.BODY, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (user.isAdmin) {
                                    Spacer(Modifier.width(8.dp))
                                    AppText(
                                        Strings[StringKey.USER_MGMT_SUPER_ADMIN_BADGE],
                                        AppTextStyle.TINY,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (!user.isAdmin) {
                                val summary = user.grants.joinToString("  ·  ") {
                                    val cc = it.clientCode?.let { c -> " ($c)" } ?: ""
                                    "${it.stationId}: ${AppRole.parse(it.role).toStringKey().localized()}$cc"
                                }.ifEmpty { Strings[StringKey.USER_MGMT_NO_ACCESS] }
                                AppText(summary, AppTextStyle.NOTE)
                            }
                        }
                        if (!user.isAdmin) {
                            IconButton(onClick = { onIntent(UserManagementIntent.ResetRequested(user)) }) {
                                Icon(AppIcons.lockReset, contentDescription = Strings[StringKey.USER_MGMT_CD_RESET_PASSWORD])
                            }
                            IconButton(onClick = { onIntent(UserManagementIntent.EditGrantsRequested(user)) }) {
                                Icon(AppIcons.edit, contentDescription = Strings[StringKey.USER_MGMT_CD_EDIT_GRANTS])
                            }
                            IconButton(onClick = { onIntent(UserManagementIntent.DeleteRequested(user)) }) {
                                Icon(
                                    AppIcons.delete,
                                    contentDescription = Strings[StringKey.USER_MGMT_CD_DELETE_USER],
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    state.create?.let { dialog ->
        CreateUserDialog(dialog = dialog, stationChoices = stationChoices, onIntent = onIntent)
    }
    state.reset?.let { dialog ->
        ResetPasswordDialog(dialog = dialog, onIntent = onIntent)
    }
    state.editGrants?.let { dialog ->
        EditGrantsDialog(dialog = dialog, stationChoices = stationChoices, onIntent = onIntent)
    }
    state.delete?.let { user ->
        AlertDialog(
            onDismissRequest = { onIntent(UserManagementIntent.DismissDelete) },
            title = { AppText(Strings[StringKey.USER_MGMT_DELETE_TITLE].withArgs(listOf(user.username)), AppTextStyle.DIALOG_TITLE) },
            text = { AppText(Strings[StringKey.USER_MGMT_DELETE_BODY], AppTextStyle.BODY) },
            confirmButton = {
                TextButton(onClick = { onIntent(UserManagementIntent.ConfirmDelete) }) {
                    AppText(Strings[StringKey.COMMON_DELETE], AppTextStyle.BUTTON, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(UserManagementIntent.DismissDelete) }) { AppText(Strings[StringKey.COMMON_CANCEL], AppTextStyle.BUTTON) }
            }
        )
    }
}

/**
 * Per-station role picker used by create/edit dialogs; emits role/clientCode
 * changes through the given intents.
 */
@Composable
private fun GrantsEditor(
    stationChoices: List<Pair<String, String>>,
    grants: GrantsSelection,
    onRoleChanged: (stationId: String, role: String) -> Unit,
    onCodeChanged: (stationId: String, code: String) -> Unit,
) {
    stationChoices.forEach { (stationId, stationName) ->
        var expanded by remember { mutableStateOf(false) }
        val role = grants.roles[stationId] ?: NO_ACCESS
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppText(stationName, AppTextStyle.BODY, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { expanded = true }) {
                AppText(
                    if (role == NO_ACCESS) Strings[StringKey.USER_MGMT_NO_ACCESS]
                    else Strings[AppRole.parse(role).toStringKey()],
                    AppTextStyle.NOTE,
                    color = LocalContentColor.current,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                (listOf(NO_ACCESS) + AppRole.entries.map { it.name }).forEach { option ->
                    DropdownMenuItem(
                        text = { AppText(if (option == NO_ACCESS) Strings[StringKey.USER_MGMT_NO_ACCESS] else Strings[AppRole.parse(option).toStringKey()], AppTextStyle.BUTTON) },
                        onClick = {
                            onRoleChanged(stationId, option)
                            expanded = false
                        }
                    )
                }
            }
        }
        if (role == AppRole.CUSTOMER_VIEWER.name) {
            OutlinedTextField(
                value = grants.clientCodes[stationId] ?: "",
                onValueChange = { onCodeChanged(stationId, it) },
                label = { AppText(Strings[StringKey.USER_MGMT_CLIENT_CODE_ON].withArgs(listOf(stationName)), AppTextStyle.FIELD_LABEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CreateUserDialog(
    dialog: CreateUserDialogState,
    stationChoices: List<Pair<String, String>>,
    onIntent: (UserManagementIntent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onIntent(UserManagementIntent.DismissCreate) },
        title = { AppText(Strings[StringKey.USER_MGMT_NEW_USER_TITLE], AppTextStyle.DIALOG_TITLE) },
        text = {
            Column {
                OutlinedTextField(
                    value = dialog.username,
                    onValueChange = { onIntent(UserManagementIntent.CreateUsernameChanged(it)) },
                    label = { AppText(Strings[StringKey.LOGIN_USERNAME], AppTextStyle.FIELD_LABEL) }, singleLine = true,
                    enabled = !dialog.busy, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dialog.displayName,
                    onValueChange = { onIntent(UserManagementIntent.CreateDisplayNameChanged(it)) },
                    label = { AppText(Strings[StringKey.USER_MGMT_DISPLAY_NAME], AppTextStyle.FIELD_LABEL) }, singleLine = true,
                    enabled = !dialog.busy, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dialog.password,
                    onValueChange = { onIntent(UserManagementIntent.CreatePasswordChanged(it)) },
                    label = { AppText(Strings[StringKey.USER_MGMT_INITIAL_PASSWORD], AppTextStyle.FIELD_LABEL) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, enabled = !dialog.busy, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                AppText(Strings[StringKey.USER_MGMT_STATION_ACCESS], AppTextStyle.BODY_STRONG)
                GrantsEditor(
                    stationChoices = stationChoices,
                    grants = dialog.grants,
                    onRoleChanged = { st, role -> onIntent(UserManagementIntent.CreateGrantRoleChanged(st, role)) },
                    onCodeChanged = { st, code -> onIntent(UserManagementIntent.CreateClientCodeChanged(st, code)) },
                )
                dialog.error?.let {
                    Spacer(Modifier.height(8.dp))
                    AppText(it.asString(), AppTextStyle.ERROR_NOTE)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = dialog.canSubmit,
                onClick = { onIntent(UserManagementIntent.ConfirmCreate) }
            ) { AppText(Strings[StringKey.USER_MGMT_CREATE], AppTextStyle.BUTTON) }
        },
        dismissButton = {
            TextButton(enabled = !dialog.busy, onClick = { onIntent(UserManagementIntent.DismissCreate) }) { AppText(Strings[StringKey.COMMON_CANCEL], AppTextStyle.BUTTON) }
        }
    )
}

@Composable
private fun ResetPasswordDialog(
    dialog: ResetPasswordDialogState,
    onIntent: (UserManagementIntent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onIntent(UserManagementIntent.DismissReset) },
        title = { AppText(Strings[StringKey.USER_MGMT_RESET_TITLE].withArgs(listOf(dialog.user.username)), AppTextStyle.DIALOG_TITLE) },
        text = {
            Column {
                AppText(Strings[StringKey.USER_MGMT_RESET_INFO], AppTextStyle.BODY)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dialog.password,
                    onValueChange = { onIntent(UserManagementIntent.ResetPasswordChanged(it)) },
                    label = { AppText(Strings[StringKey.USER_MGMT_NEW_PASSWORD_MIN], AppTextStyle.FIELD_LABEL) },
                    singleLine = true, enabled = !dialog.busy, modifier = Modifier.fillMaxWidth()
                )
                dialog.error?.let {
                    Spacer(Modifier.height(8.dp))
                    AppText(it.asString(), AppTextStyle.ERROR_NOTE)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = dialog.canSubmit,
                onClick = { onIntent(UserManagementIntent.ConfirmReset) }
            ) { AppText(Strings[StringKey.USER_MGMT_RESET], AppTextStyle.BUTTON) }
        },
        dismissButton = {
            TextButton(enabled = !dialog.busy, onClick = { onIntent(UserManagementIntent.DismissReset) }) { AppText(Strings[StringKey.COMMON_CANCEL], AppTextStyle.BUTTON) }
        }
    )
}

@Composable
private fun EditGrantsDialog(
    dialog: EditGrantsDialogState,
    stationChoices: List<Pair<String, String>>,
    onIntent: (UserManagementIntent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onIntent(UserManagementIntent.DismissGrants) },
        title = { AppText(Strings[StringKey.USER_MGMT_GRANTS_TITLE].withArgs(listOf(dialog.user.username)), AppTextStyle.DIALOG_TITLE) },
        text = {
            Column {
                GrantsEditor(
                    stationChoices = stationChoices,
                    grants = dialog.grants,
                    onRoleChanged = { st, role -> onIntent(UserManagementIntent.GrantRoleChanged(st, role)) },
                    onCodeChanged = { st, code -> onIntent(UserManagementIntent.GrantClientCodeChanged(st, code)) },
                )
                dialog.error?.let {
                    Spacer(Modifier.height(8.dp))
                    AppText(it.asString(), AppTextStyle.ERROR_NOTE)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !dialog.busy,
                onClick = { onIntent(UserManagementIntent.ConfirmGrants) }
            ) { AppText(Strings[StringKey.COMMON_SAVE], AppTextStyle.BUTTON) }
        },
        dismissButton = {
            TextButton(enabled = !dialog.busy, onClick = { onIntent(UserManagementIntent.DismissGrants) }) { AppText(Strings[StringKey.COMMON_CANCEL], AppTextStyle.BUTTON) }
        }
    )
}
