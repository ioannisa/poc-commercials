package eu.anifantakis.commercials.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.anifantakis.commercials.auth.AuthApi
import kotlinx.coroutines.launch

/**
 * Self-service password change. On success the server revokes every session
 * of the user and AuthApi clears the local one - the session-revision
 * observer then routes the app back to Login automatically.
 */
@Composable
fun ChangePasswordDialog(authApi: AuthApi, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Change password") },
        text = {
            Column {
                OutlinedTextField(
                    value = current, onValueChange = { current = it },
                    label = { Text("Current password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = new1, onValueChange = { new1 = it },
                    label = { Text("New password (min 6 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = new2, onValueChange = { new2 = it },
                    label = { Text("Repeat new password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "After the change every session is signed out - log in with the new password.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && current.isNotBlank() && new1.length >= 6 && new1 == new2,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        authApi.changePassword(current, new1)
                            .onSuccess { onDismiss() }   // session cleared -> Login screen
                            .onFailure { error = it.message; busy = false }
                    }
                }
            ) {
                if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                else Text("Change")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Regenerates one-time recovery codes and shows them EXACTLY ONCE - the
 * server keeps only hashes. Old codes stop working immediately.
 */
@Composable
fun RecoveryCodesDialog(authApi: AuthApi, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var codes by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Recovery codes") },
        text = {
            Column {
                if (codes == null) {
                    Text(
                        "Generate 6 one-time codes for the \"forgot password\" flow. " +
                            "They are shown ONCE - store them somewhere safe. " +
                            "Generating new codes invalidates all previous ones.",
                        fontSize = 13.sp
                    )
                } else {
                    Text(
                        "Save these now - they will never be shown again:",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    codes?.forEach { code ->
                        Text(code, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            if (codes == null) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            authApi.regenerateRecoveryCodes()
                                .onSuccess { codes = it }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    }
                ) {
                    if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else Text("Generate new codes")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("I saved them") }
            }
        },
        dismissButton = {
            if (codes == null) TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        }
    )
}
