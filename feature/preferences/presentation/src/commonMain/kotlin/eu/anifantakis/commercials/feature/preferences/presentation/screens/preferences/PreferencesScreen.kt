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
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.string_resources.Language
import eu.anifantakis.commercials.core.presentation.string_resources.LocalLanguage
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.feature.preferences.domain.FontSizePreference
import eu.anifantakis.commercials.feature.preferences.domain.ThemePreference
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

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
        fontSize = viewModel.fontSize,
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
    fontSize: FontSizePreference,
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
                AppText(Strings[StringKey.PREFERENCES_TITLE], AppTextStyle.SCREEN_TITLE)
            }

            Spacer(Modifier.height(8.dp))

            // ── appearance ──────────────────────────────────────────────
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    AppText(Strings[StringKey.PREFERENCES_APPEARANCE], AppTextStyle.SECTION_TITLE)
                    Spacer(Modifier.height(4.dp))
                    ThemeOption(theme, ThemePreference.LIGHT, Strings[StringKey.PREFERENCES_THEME_LIGHT], Strings[StringKey.PREFERENCES_THEME_LIGHT_DESC], onIntent)
                    ThemeOption(
                        theme, ThemePreference.DARK, Strings[StringKey.PREFERENCES_THEME_DARK],
                        Strings[StringKey.PREFERENCES_THEME_DARK_DESC], onIntent
                    )
                    ThemeOption(theme, ThemePreference.SYSTEM, Strings[StringKey.PREFERENCES_THEME_SYSTEM], Strings[StringKey.PREFERENCES_THEME_SYSTEM_DESC], onIntent)

                    Spacer(Modifier.height(12.dp))

                    // Text size: 5 discrete steps; the whole app restyles live
                    // as the slider moves (the theme rebuilds its typography).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(Strings[StringKey.PREFERENCES_FONT_SIZE], AppTextStyle.BODY_STRONG)
                        Spacer(Modifier.weight(1f))
                        AppText(fontSize.displayName(), AppTextStyle.NOTE)
                    }
                    Slider(
                        value = fontSize.ordinal.toFloat(),
                        onValueChange = { raw ->
                            val step = FontSizePreference.entries[raw.roundToInt()
                                .coerceIn(0, FontSizePreference.entries.lastIndex)]
                            if (step != fontSize) onIntent(PreferencesIntent.FontSizeSelected(step))
                        },
                        valueRange = 0f..FontSizePreference.entries.lastIndex.toFloat(),
                        steps = FontSizePreference.entries.size - 2,
                    )
                    AppText(Strings[StringKey.PREFERENCES_FONT_SIZE_PREVIEW], AppTextStyle.BODY)
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── language ─────────────────────────────────────────────────
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    AppText(Strings[StringKey.PREFERENCES_LANGUAGE], AppTextStyle.SECTION_TITLE)
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
                        AppText(Strings[StringKey.PREFERENCES_ACCOUNT], AppTextStyle.SECTION_TITLE)
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
                        AppText(Strings[StringKey.PREFERENCES_MAINTENANCE], AppTextStyle.SECTION_TITLE)
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

/** The step's localized display name, shown beside the slider. */
@Composable
private fun FontSizePreference.displayName(): String = when (this) {
    FontSizePreference.XSMALL -> Strings[StringKey.PREFERENCES_FONT_XSMALL]
    FontSizePreference.SMALL -> Strings[StringKey.PREFERENCES_FONT_SMALL]
    FontSizePreference.MEDIUM -> Strings[StringKey.PREFERENCES_FONT_MEDIUM]
    FontSizePreference.LARGE -> Strings[StringKey.PREFERENCES_FONT_LARGE]
    FontSizePreference.XLARGE -> Strings[StringKey.PREFERENCES_FONT_XLARGE]
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
            AppText(label, AppTextStyle.BODY)
            AppText(description, AppTextStyle.TINY)
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
        AppText(value.displayName, AppTextStyle.BODY)
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
            AppText(label, AppTextStyle.BODY)
            AppText(description, AppTextStyle.TINY)
        }
    }
}
