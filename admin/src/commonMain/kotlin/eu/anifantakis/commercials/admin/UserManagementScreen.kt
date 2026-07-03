package eu.anifantakis.commercials.admin

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.anifantakis.commercials.auth.AppRole
import eu.anifantakis.commercials.auth.AuthSession
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val NO_ACCESS = "NO ACCESS"

/**
 * Super-admin user management: list, create, reset password, edit per-station
 * grants, delete. The station list comes from the admin's own session (the
 * super admin has implicit access to every hosted station).
 */
@Composable
fun UserManagementScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val adminApi = koinInject<AdminApi>()
    val authSession = koinInject<AuthSession>()

    var users by remember { mutableStateOf<List<AdminUser>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var resetTarget by remember { mutableStateOf<AdminUser?>(null) }
    var grantsTarget by remember { mutableStateOf<AdminUser?>(null) }
    var deleteTarget by remember { mutableStateOf<AdminUser?>(null) }

    fun reload() {
        scope.launch {
            adminApi.listUsers()
                .onSuccess { users = it; }
                .onFailure { message = it.message }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("User Management", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Button(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New User")
            }
        }

        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(users, key = { it.id }) { user ->
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
                                        "SUPER ADMIN (stations.yaml)",
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
                            IconButton(onClick = { resetTarget = user }) {
                                Icon(Icons.Default.LockReset, contentDescription = "Reset password")
                            }
                            IconButton(onClick = { grantsTarget = user }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit grants")
                            }
                            IconButton(onClick = { deleteTarget = user }) {
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

    if (showCreate) {
        CreateUserDialog(
            adminApi = adminApi,
            stationChoices = authSession.stations.map { it.id to it.name },
            onDone = { showCreate = false; reload() },
            onDismiss = { showCreate = false }
        )
    }

    resetTarget?.let { user ->
        ResetPasswordDialog(
            adminApi = adminApi,
            user = user,
            onDone = { resetTarget = null; reload() },
            onDismiss = { resetTarget = null }
        )
    }

    grantsTarget?.let { user ->
        EditGrantsDialog(
            adminApi = adminApi,
            user = user,
            stationChoices = authSession.stations.map { it.id to it.name },
            onDone = { grantsTarget = null; reload() },
            onDismiss = { grantsTarget = null }
        )
    }

    deleteTarget?.let { user ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete '${user.username}'?") },
            text = { Text("Their sessions, grants and recovery codes are removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        adminApi.deleteUser(user.id).onFailure { message = it.message }
                        deleteTarget = null
                        reload()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

/**
 * Per-station role picker used by create/edit dialogs. Selection state is a
 * map stationId -> (role name or NO_ACCESS) plus clientCode for customers.
 */
@Composable
private fun GrantsEditor(
    stationChoices: List<Pair<String, String>>,
    roles: MutableMap<String, String>,
    clientCodes: MutableMap<String, String>,
) {
    stationChoices.forEach { (stationId, stationName) ->
        var expanded by remember { mutableStateOf(false) }
        val role = roles[stationId] ?: NO_ACCESS
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
                            roles[stationId] = option
                            expanded = false
                        }
                    )
                }
            }
        }
        if (role == AppRole.CUSTOMER_VIEWER.name) {
            OutlinedTextField(
                value = clientCodes[stationId] ?: "",
                onValueChange = { clientCodes[stationId] = it },
                label = { Text("Client code on $stationName") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun collectGrants(
    roles: Map<String, String>,
    clientCodes: Map<String, String>,
): List<AdminGrant> = roles.mapNotNull { (stationId, role) ->
    if (role == NO_ACCESS) null
    else AdminGrant(stationId, role, clientCodes[stationId]?.trim()?.ifEmpty { null })
}

@Composable
private fun CreateUserDialog(
    adminApi: AdminApi,
    stationChoices: List<Pair<String, String>>,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val roles = remember { mutableStateMapOf<String, String>() }
    val clientCodes = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New user") },
        text = {
            Column {
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Username") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = displayName, onValueChange = { displayName = it },
                    label = { Text("Display name") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Initial password (min 6 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Station access", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                GrantsEditor(stationChoices, roles, clientCodes)
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && username.isNotBlank() && displayName.isNotBlank() && password.length >= 6,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        adminApi.createUser(username.trim(), displayName.trim(), password, collectGrants(roles, clientCodes))
                            .onSuccess { onDone() }
                            .onFailure { error = it.message; busy = false }
                    }
                }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ResetPasswordDialog(
    adminApi: AdminApi,
    user: AdminUser,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Reset password for '${user.username}'") },
        text = {
            Column {
                Text(
                    "Set a temporary password and hand it to the user - " +
                        "their existing sessions are signed out.",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("New password (min 6 chars)") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && password.length >= 6,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        adminApi.resetPassword(user.id, password)
                            .onSuccess { onDone() }
                            .onFailure { error = it.message; busy = false }
                    }
                }
            ) { Text("Reset") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EditGrantsDialog(
    adminApi: AdminApi,
    user: AdminUser,
    stationChoices: List<Pair<String, String>>,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val roles = remember {
        mutableStateMapOf<String, String>().apply {
            user.grants.forEach { put(it.stationId, it.role) }
        }
    }
    val clientCodes = remember {
        mutableStateMapOf<String, String>().apply {
            user.grants.forEach { g -> g.clientCode?.let { put(g.stationId, it) } }
        }
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Station access for '${user.username}'") },
        text = {
            Column {
                GrantsEditor(stationChoices, roles, clientCodes)
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        adminApi.setGrants(user.id, collectGrants(roles, clientCodes))
                            .onSuccess { onDone() }
                            .onFailure { error = it.message; busy = false }
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}
