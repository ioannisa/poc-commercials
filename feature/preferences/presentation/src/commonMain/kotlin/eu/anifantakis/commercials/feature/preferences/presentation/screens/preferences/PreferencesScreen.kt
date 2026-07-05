package eu.anifantakis.commercials.feature.preferences.presentation.screens.preferences

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.anifantakis.commercials.feature.preferences.domain.ThemePreference
import org.koin.compose.viewmodel.koinViewModel

/**
 * The gear-icon screen: theme selection (persisted, applied live) plus the
 * entry points to account self-service and super-admin maintenance. The
 * account dialogs belong to :feature:auth - this screen only emits the
 * callbacks; the app layer renders them.
 */
@Composable
fun PreferencesScreenRoot(
    isAdmin: Boolean,
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onRecoveryCodes: () -> Unit,
    onManageUsers: () -> Unit,
    onMigration: () -> Unit,
    onDatabases: () -> Unit,
    viewModel: PreferencesViewModel = koinViewModel(),
) {
    PreferencesScreen(
        theme = viewModel.theme,
        isAdmin = isAdmin,
        onIntent = viewModel::onAction,
        onNavIntent = { navIntent ->
            when (navIntent) {
                PreferencesScreenNavIntent.OnBack -> onBack()
                PreferencesScreenNavIntent.OnChangePassword -> onChangePassword()
                PreferencesScreenNavIntent.OnRecoveryCodes -> onRecoveryCodes()
                PreferencesScreenNavIntent.OnManageUsers -> onManageUsers()
                PreferencesScreenNavIntent.OnMigration -> onMigration()
                PreferencesScreenNavIntent.OnDatabases -> onDatabases()
            }
        },
    )
}

/**
 * Navigation-only actions of the preferences screen. Not ViewModel
 * [PreferencesIntent]s (they touch no state), collapsed into ONE parameter
 * so six nav callbacks don't bloat the signature. The Root maps each to the
 * nav callback it received.
 */
private sealed interface PreferencesScreenNavIntent {
    data object OnBack : PreferencesScreenNavIntent
    data object OnChangePassword : PreferencesScreenNavIntent
    data object OnRecoveryCodes : PreferencesScreenNavIntent
    data object OnManageUsers : PreferencesScreenNavIntent
    data object OnMigration : PreferencesScreenNavIntent
    data object OnDatabases : PreferencesScreenNavIntent
}

@Composable
private fun PreferencesScreen(
    theme: ThemePreference,
    isAdmin: Boolean,
    onIntent: (PreferencesIntent) -> Unit,
    onNavIntent: (PreferencesScreenNavIntent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onNavIntent(PreferencesScreenNavIntent.OnBack) }) {
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
                    ThemeOption(theme, ThemePreference.LIGHT, "Light", "Always light", onIntent)
                    ThemeOption(
                        theme, ThemePreference.DARK, "Dark",
                        "Always dark (the scheduler keeps its light paper surface)", onIntent
                    )
                    ThemeOption(theme, ThemePreference.SYSTEM, "System", "Follow the operating system setting", onIntent)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── account (server refuses these for the YAML super admin) ─
            if (!isAdmin) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Account", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        PreferenceEntry(Icons.Default.Lock, "Change Password", "Signs out every session") { onNavIntent(PreferencesScreenNavIntent.OnChangePassword) }
                        PreferenceEntry(Icons.Default.Key, "Recovery Codes", "One-time codes for \"forgot password\"") { onNavIntent(PreferencesScreenNavIntent.OnRecoveryCodes) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── maintenance (super admin) ───────────────────────────────
            if (isAdmin) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Maintenance", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        PreferenceEntry(Icons.Default.ManageAccounts, "Manage Users", "Accounts, grants, password resets") { onNavIntent(PreferencesScreenNavIntent.OnManageUsers) }
                        PreferenceEntry(Icons.Default.Storage, "Legacy Migration", "Import a legacy mysqldump as a station") { onNavIntent(PreferencesScreenNavIntent.OnMigration) }
                        PreferenceEntry(Icons.Default.Dns, "Hosted Databases", "Inspect and delete hosted stations") { onNavIntent(PreferencesScreenNavIntent.OnDatabases) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeOption(
    current: ThemePreference,
    value: ThemePreference,
    label: String,
    description: String,
    onIntent: (PreferencesIntent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onIntent(PreferencesIntent.ThemeSelected(value)) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = current == value, onClick = { onIntent(PreferencesIntent.ThemeSelected(value)) })
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
