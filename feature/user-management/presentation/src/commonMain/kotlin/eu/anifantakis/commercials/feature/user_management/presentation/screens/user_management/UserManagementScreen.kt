package eu.anifantakis.commercials.feature.user_management.presentation.screens.user_management

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        onBack = onBack,
    )
}

@Composable
private fun UserManagementScreen(
    state: UserManagementState,
    stationChoices: List<Pair<String, String>>,
    onIntent: (UserManagementIntent) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("User Management", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Button(onClick = { onIntent(UserManagementIntent.ShowCreate) }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New User")
            }
        }

        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
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
                                Text(user.username, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(user.displayName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (user.isAdmin) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "SUPER ADMIN (server.yaml)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (!user.isAdmin) {
                                val summary = user.grants.joinToString("  ·  ") {
                                    val cc = it.clientCode?.let { c -> " ($c)" } ?: ""
                                    "${it.stationId}: ${AppRole.parse(it.role).label}$cc"
                                }.ifEmpty { "no station access" }
                                Text(summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!user.isAdmin) {
                            IconButton(onClick = { onIntent(UserManagementIntent.ResetRequested(user)) }) {
                                Icon(Icons.Default.LockReset, contentDescription = "Reset password")
                            }
                            IconButton(onClick = { onIntent(UserManagementIntent.EditGrantsRequested(user)) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit grants")
                            }
                            IconButton(onClick = { onIntent(UserManagementIntent.DeleteRequested(user)) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete user",
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
            title = { Text("Delete '${user.username}'?") },
            text = { Text("Their sessions, grants and recovery codes are removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { onIntent(UserManagementIntent.ConfirmDelete) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(UserManagementIntent.DismissDelete) }) { Text("Cancel") }
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
            Text(stationName, fontSize = 13.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { expanded = true }) {
                Text(
                    if (role == NO_ACCESS) NO_ACCESS else AppRole.parse(role).label,
                    fontSize = 12.sp
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                (listOf(NO_ACCESS) + AppRole.entries.map { it.name }).forEach { option ->
                    DropdownMenuItem(
                        text = { Text(if (option == NO_ACCESS) NO_ACCESS else AppRole.parse(option).label) },
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
                label = { Text("Client code on $stationName") },
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
        title = { Text("New user") },
        text = {
            Column {
                OutlinedTextField(
                    value = dialog.username,
                    onValueChange = { onIntent(UserManagementIntent.CreateUsernameChanged(it)) },
                    label = { Text("Username") }, singleLine = true,
                    enabled = !dialog.busy, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dialog.displayName,
                    onValueChange = { onIntent(UserManagementIntent.CreateDisplayNameChanged(it)) },
                    label = { Text("Display name") }, singleLine = true,
                    enabled = !dialog.busy, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dialog.password,
                    onValueChange = { onIntent(UserManagementIntent.CreatePasswordChanged(it)) },
                    label = { Text("Initial password (min 6 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, enabled = !dialog.busy, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Station access", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                GrantsEditor(
                    stationChoices = stationChoices,
                    grants = dialog.grants,
                    onRoleChanged = { st, role -> onIntent(UserManagementIntent.CreateGrantRoleChanged(st, role)) },
                    onCodeChanged = { st, code -> onIntent(UserManagementIntent.CreateClientCodeChanged(st, code)) },
                )
                dialog.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = dialog.canSubmit,
                onClick = { onIntent(UserManagementIntent.ConfirmCreate) }
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(enabled = !dialog.busy, onClick = { onIntent(UserManagementIntent.DismissCreate) }) { Text("Cancel") }
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
        title = { Text("Reset password for '${dialog.user.username}'") },
        text = {
            Column {
                Text(
                    "Set a temporary password and hand it to the user - " +
                        "their existing sessions are signed out.",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dialog.password,
                    onValueChange = { onIntent(UserManagementIntent.ResetPasswordChanged(it)) },
                    label = { Text("New password (min 6 chars)") },
                    singleLine = true, enabled = !dialog.busy, modifier = Modifier.fillMaxWidth()
                )
                dialog.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = dialog.canSubmit,
                onClick = { onIntent(UserManagementIntent.ConfirmReset) }
            ) { Text("Reset") }
        },
        dismissButton = {
            TextButton(enabled = !dialog.busy, onClick = { onIntent(UserManagementIntent.DismissReset) }) { Text("Cancel") }
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
        title = { Text("Station access for '${dialog.user.username}'") },
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
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !dialog.busy,
                onClick = { onIntent(UserManagementIntent.ConfirmGrants) }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(enabled = !dialog.busy, onClick = { onIntent(UserManagementIntent.DismissGrants) }) { Text("Cancel") }
        }
    )
}
