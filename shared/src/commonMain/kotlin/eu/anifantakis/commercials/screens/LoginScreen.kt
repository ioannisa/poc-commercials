package eu.anifantakis.commercials.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.anifantakis.commercials.auth.AuthApi
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Login gate shown before any data is loaded. On success the session (token,
 * stations, roles) is already stored in AuthSession by [AuthApi.login];
 * [onLoggedIn] then swaps the navigation stack to the main app.
 *
 * Also hosts the "forgot password" flow: username + ONE unused recovery code
 * (see the account menu, "Recovery codes") sets a new password. Users without
 * saved codes are reset by the administrator instead.
 */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    val authApi = koinInject<AuthApi>()

    var recoveryMode by remember { mutableStateOf(false) }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

    fun submitLogin() {
        if (isLoading || username.isBlank() || password.isBlank()) return
        isLoading = true
        errorMessage = null
        scope.launch {
            authApi.login(username.trim(), password)
                .onSuccess { onLoggedIn() }
                .onFailure { errorMessage = it.message }
            isLoading = false
        }
    }

    fun submitRecovery() {
        if (isLoading || username.isBlank() || recoveryCode.isBlank() || password.isBlank()) return
        isLoading = true
        errorMessage = null
        scope.launch {
            authApi.recoverPassword(username.trim(), recoveryCode, password)
                .onSuccess {
                    recoveryMode = false
                    password = ""
                    recoveryCode = ""
                    infoMessage = "Password reset - log in with your new password"
                }
                .onFailure { errorMessage = it.message }
            isLoading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Commercials Manager",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (recoveryMode) "Επαναφορά Κωδικού" else "Σύνδεση",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                if (recoveryMode) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = recoveryCode,
                        onValueChange = { recoveryCode = it },
                        label = { Text("Recovery code") },
                        placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (recoveryMode) "New password" else "Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    visualTransformation =
                        if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
                infoMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp
                    )
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = { if (recoveryMode) submitRecovery() else submitLogin() },
                    enabled = !isLoading && username.isNotBlank() && password.isNotBlank() &&
                        (!recoveryMode || recoveryCode.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (recoveryMode) "Reset password" else "Login")
                }

                Spacer(Modifier.height(8.dp))

                TextButton(onClick = {
                    recoveryMode = !recoveryMode
                    errorMessage = null
                    infoMessage = null
                    password = ""
                }) {
                    Text(
                        if (recoveryMode) "Back to login"
                        else "Forgot password? Use a recovery code",
                        fontSize = 12.sp
                    )
                }

                if (!recoveryMode) {
                    Spacer(Modifier.height(16.dp))
                    // Demo accounts - one per access layer (POC convenience)
                    Text(
                        text = "Demo accounts",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "admin / admin123 — Normal User\n" +
                            "viewer / viewer123 — Report Viewer\n" +
                            "customer / customer123 — Customer Viewer\n" +
                            "superadmin — see stations.yaml",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
