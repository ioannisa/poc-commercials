package eu.anifantakis.commercials.feature.preferences.presentation.screens.preferences

import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
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
import eu.anifantakis.commercials.core.presentation.string_resources.Language
import eu.anifantakis.commercials.core.presentation.string_resources.LocalLanguage
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
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
        language = LocalLanguage.current ?: Language.FALLBACK,
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
    language: Language,
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
                    Icon(AppIcons.arrowBack, contentDescription = Strings[StringKey.COMMON_BACK])
                }
                Text(Strings[StringKey.PREFERENCES_TITLE], fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))

            // ── appearance ──────────────────────────────────────────────
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(Strings[StringKey.PREFERENCES_APPEARANCE], fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    ThemeOption(theme, ThemePreference.LIGHT, Strings[StringKey.PREFERENCES_THEME_LIGHT], Strings[StringKey.PREFERENCES_THEME_LIGHT_DESC], onIntent)
                    ThemeOption(
                        theme, ThemePreference.DARK, Strings[StringKey.PREFERENCES_THEME_DARK],
                        Strings[StringKey.PREFERENCES_THEME_DARK_DESC], onIntent
                    )
                    ThemeOption(theme, ThemePreference.SYSTEM, Strings[StringKey.PREFERENCES_THEME_SYSTEM], Strings[StringKey.PREFERENCES_THEME_SYSTEM_DESC], onIntent)
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── language ─────────────────────────────────────────────────
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(Strings[StringKey.PREFERENCES_LANGUAGE], fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Language.entries.forEach { lang ->
                        LanguageOption(language, lang, onIntent)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── account (server refuses these for the YAML super admin) ─
            if (!isAdmin) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(Strings[StringKey.PREFERENCES_ACCOUNT], fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        PreferenceEntry(AppIcons.lock, Strings[StringKey.PREFERENCES_CHANGE_PASSWORD], Strings[StringKey.PREFERENCES_CHANGE_PASSWORD_DESC]) { onNavIntent(PreferencesScreenNavIntent.OnChangePassword) }
                        PreferenceEntry(AppIcons.key, Strings[StringKey.PREFERENCES_RECOVERY_CODES], Strings[StringKey.PREFERENCES_RECOVERY_CODES_DESC]) { onNavIntent(PreferencesScreenNavIntent.OnRecoveryCodes) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── maintenance (super admin) ───────────────────────────────
            if (isAdmin) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(Strings[StringKey.PREFERENCES_MAINTENANCE], fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        PreferenceEntry(AppIcons.manageAccounts, Strings[StringKey.PREFERENCES_MANAGE_USERS], Strings[StringKey.PREFERENCES_MANAGE_USERS_DESC]) { onNavIntent(PreferencesScreenNavIntent.OnManageUsers) }
                        PreferenceEntry(AppIcons.storage, Strings[StringKey.PREFERENCES_MIGRATION], Strings[StringKey.PREFERENCES_MIGRATION_DESC]) { onNavIntent(PreferencesScreenNavIntent.OnMigration) }
                        PreferenceEntry(AppIcons.dns, Strings[StringKey.PREFERENCES_DATABASES], Strings[StringKey.PREFERENCES_DATABASES_DESC]) { onNavIntent(PreferencesScreenNavIntent.OnDatabases) }
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
private fun LanguageOption(
    current: Language,
    value: Language,
    onIntent: (PreferencesIntent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onIntent(PreferencesIntent.LanguageSelected(value)) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = current == value, onClick = { onIntent(PreferencesIntent.LanguageSelected(value)) })
        Text(value.displayName, fontSize = 14.sp)
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
