package eu.anifantakis.commercials.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.anifantakis.commercials.auth.AuthApi
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.prefs.ThemePreference
import eu.anifantakis.commercials.prefs.UserPreferences
import org.koin.compose.koinInject

/**
 * The gear-icon screen: theme selection (persisted in KSafe, applied live)
 * plus the entry points that used to hide in the account-badge menu -
 * self-service account actions for everyone, maintenance for the super admin.
 */
@Composable
fun PreferencesScreen(
    onBack: () -> Unit,
    onManageUsers: () -> Unit,
    onMigration: () -> Unit,
    onDatabases: () -> Unit,
) {
    val prefs = koinInject<UserPreferences>()
    val authSession = koinInject<AuthSession>()
    val authApi = koinInject<AuthApi>()

    var showChangePassword by remember { mutableStateOf(false) }
    var showRecoveryCodes by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Preferences", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))

            // ── appearance ──────────────────────────────────────────────
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Appearance", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    ThemeOption(prefs, ThemePreference.LIGHT, "Light", "Always light")
                    ThemeOption(prefs, ThemePreference.DARK, "Dark", "Always dark (the scheduler keeps its light paper surface)")
                    ThemeOption(prefs, ThemePreference.SYSTEM, "System", "Follow the operating system setting")
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── account (server refuses these for the YAML super admin) ─
            if (!authSession.isAdmin) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Account", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        PreferenceEntry(Icons.Default.Lock, "Change Password", "Signs out every session") {
                            showChangePassword = true
                        }
                        PreferenceEntry(Icons.Default.Key, "Recovery Codes", "One-time codes for \"forgot password\"") {
                            showRecoveryCodes = true
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── maintenance (super admin) ───────────────────────────────
            if (authSession.isAdmin) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Maintenance", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        PreferenceEntry(Icons.Default.ManageAccounts, "Manage Users", "Accounts, grants, password resets", onManageUsers)
                        PreferenceEntry(Icons.Default.Storage, "Legacy Migration", "Import a legacy mysqldump as a station", onMigration)
                        PreferenceEntry(Icons.Default.Dns, "Hosted Databases", "Inspect and delete hosted stations", onDatabases)
                    }
                }
            }
        }
    }

    if (showChangePassword) {
        ChangePasswordDialog(authApi = authApi, onDismiss = { showChangePassword = false })
    }
    if (showRecoveryCodes) {
        RecoveryCodesDialog(authApi = authApi, onDismiss = { showRecoveryCodes = false })
    }
}

@Composable
private fun ThemeOption(
    prefs: UserPreferences,
    value: ThemePreference,
    label: String,
    description: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { prefs.theme = value }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = prefs.theme == value, onClick = { prefs.theme = value })
        Column {
            Text(label, fontSize = 14.sp)
            Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PreferenceEntry(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(label, fontSize = 14.sp)
            Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
