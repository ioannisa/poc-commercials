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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCard
import eu.anifantakis.commercials.core.presentation.design_system.components.AppFormColumn
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconSize
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadio
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadioRow
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import eu.anifantakis.commercials.core.presentation.string_resources.Language
import eu.anifantakis.commercials.core.presentation.string_resources.LocalLanguage
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.feature.preferences.domain.FontSizePreference
import eu.anifantakis.commercials.feature.preferences.domain.ThemePreference
import androidx.compose.ui.tooling.preview.Preview
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
    swaggerEnabled: Boolean,
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onApiTokens: () -> Unit,
    onAdminMcp: () -> Unit,
    onManageUsers: () -> Unit,
    onMigration: () -> Unit,
    onDatabases: () -> Unit,
    onOpenSwagger: () -> Unit,
    viewModel: PreferencesViewModel = koinViewModel(),
) {
    PreferencesScreen(
        theme = viewModel.theme,
        fontSize = viewModel.fontSize,
        language = LocalLanguage.current ?: Language.FALLBACK,
        isAdmin = isAdmin,
        swaggerEnabled = swaggerEnabled,
        onIntent = viewModel::onAction,
        onNavIntent = { navIntent ->
            when (navIntent) {
                PreferencesScreenNavIntent.OnBack -> onBack()
                PreferencesScreenNavIntent.OnChangePassword -> onChangePassword()
                PreferencesScreenNavIntent.OnApiTokens -> onApiTokens()
                PreferencesScreenNavIntent.OnAdminMcp -> onAdminMcp()
                PreferencesScreenNavIntent.OnManageUsers -> onManageUsers()
                PreferencesScreenNavIntent.OnMigration -> onMigration()
                PreferencesScreenNavIntent.OnDatabases -> onDatabases()
                PreferencesScreenNavIntent.OnOpenSwagger -> onOpenSwagger()
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
    data object OnApiTokens : PreferencesScreenNavIntent
    data object OnAdminMcp : PreferencesScreenNavIntent
    data object OnManageUsers : PreferencesScreenNavIntent
    data object OnMigration : PreferencesScreenNavIntent
    data object OnDatabases : PreferencesScreenNavIntent
    data object OnOpenSwagger : PreferencesScreenNavIntent
}

@Composable
private fun PreferencesScreen(
    theme: ThemePreference,
    fontSize: FontSizePreference,
    language: Language,
    isAdmin: Boolean,
    swaggerEnabled: Boolean,
    onIntent: (PreferencesIntent) -> Unit,
    onNavIntent: (PreferencesScreenNavIntent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(UIConst.paddingRegular).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 560 is this screen's own form cap (start-aligned: header row + cards).
        AppFormColumn(maxWidth = 560.dp, horizontalAlignment = Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(
                    label = Strings[StringKey.COMMON_BACK],
                    icon = AppIcons.arrowBack,
                    onClick = { onNavIntent(PreferencesScreenNavIntent.OnBack) },
                )
                AppText(Strings[StringKey.PREFERENCES_TITLE], AppTextStyle.SCREEN_TITLE)
            }

            Spacer(Modifier.height(UIConst.paddingSmall))

            // ── appearance ──────────────────────────────────────────────
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(UIConst.paddingRegular)) {
                    AppText(Strings[StringKey.PREFERENCES_APPEARANCE], AppTextStyle.SECTION_TITLE)
                    Spacer(Modifier.height(UIConst.paddingExtraSmall))
                    ThemeOption(theme, ThemePreference.LIGHT, Strings[StringKey.PREFERENCES_THEME_LIGHT], Strings[StringKey.PREFERENCES_THEME_LIGHT_DESC], onIntent)
                    ThemeOption(
                        theme, ThemePreference.DARK, Strings[StringKey.PREFERENCES_THEME_DARK],
                        Strings[StringKey.PREFERENCES_THEME_DARK_DESC], onIntent
                    )
                    ThemeOption(theme, ThemePreference.SYSTEM, Strings[StringKey.PREFERENCES_THEME_SYSTEM], Strings[StringKey.PREFERENCES_THEME_SYSTEM_DESC], onIntent)

                    Spacer(Modifier.height(UIConst.paddingCompact))

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

            Spacer(Modifier.height(UIConst.paddingSmall))

            // ── language ─────────────────────────────────────────────────
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(UIConst.paddingRegular)) {
                    AppText(Strings[StringKey.PREFERENCES_LANGUAGE], AppTextStyle.SECTION_TITLE)
                    Spacer(Modifier.height(UIConst.paddingExtraSmall))
                    Language.entries.forEach { lang ->
                        LanguageOption(language, lang, onIntent)
                    }
                }
            }

            Spacer(Modifier.height(UIConst.paddingCompact))

            // ── account (server refuses these for the YAML super admin) ─
            if (!isAdmin) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(UIConst.paddingRegular)) {
                        AppText(Strings[StringKey.PREFERENCES_ACCOUNT], AppTextStyle.SECTION_TITLE)
                        Spacer(Modifier.height(UIConst.paddingExtraSmall))
                        PreferenceEntry(AppIcons.lock, Strings[StringKey.PREFERENCES_CHANGE_PASSWORD], Strings[StringKey.PREFERENCES_CHANGE_PASSWORD_DESC]) { onNavIntent(PreferencesScreenNavIntent.OnChangePassword) }
                        PreferenceEntry(AppIcons.key, Strings[StringKey.PREFERENCES_MCP], Strings[StringKey.PREFERENCES_MCP_DESC]) { onNavIntent(PreferencesScreenNavIntent.OnApiTokens) }
                    }
                }
                Spacer(Modifier.height(UIConst.paddingCompact))
            }

            // ── maintenance (super admin) ───────────────────────────────
            if (isAdmin) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(UIConst.paddingRegular)) {
                        AppText(Strings[StringKey.PREFERENCES_MAINTENANCE], AppTextStyle.SECTION_TITLE)
                        Spacer(Modifier.height(UIConst.paddingExtraSmall))
                        PreferenceEntry(AppIcons.manageAccounts, Strings[StringKey.PREFERENCES_MANAGE_USERS], Strings[StringKey.PREFERENCES_MANAGE_USERS_DESC]) { onNavIntent(PreferencesScreenNavIntent.OnManageUsers) }
                        PreferenceEntry(AppIcons.dns, Strings[StringKey.PREFERENCES_ADMIN_MCP], Strings[StringKey.PREFERENCES_ADMIN_MCP_DESC]) { onNavIntent(PreferencesScreenNavIntent.OnAdminMcp) }
                        PreferenceEntry(AppIcons.storage, Strings[StringKey.PREFERENCES_MIGRATION], Strings[StringKey.PREFERENCES_MIGRATION_DESC]) { onNavIntent(PreferencesScreenNavIntent.OnMigration) }
                        PreferenceEntry(AppIcons.dns, Strings[StringKey.PREFERENCES_DATABASES], Strings[StringKey.PREFERENCES_DATABASES_DESC]) { onNavIntent(PreferencesScreenNavIntent.OnDatabases) }
                        PreferenceEntry(AppIcons.openInNew, Strings[StringKey.PREFERENCES_OPEN_SWAGGER], Strings[StringKey.PREFERENCES_OPEN_SWAGGER_DESC], enabled = swaggerEnabled) { onNavIntent(PreferencesScreenNavIntent.OnOpenSwagger) }
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
    // Two-line label (title + description): AppRadioRow only carries a single
    // string label, so the row stays hand-built around AppRadio.
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onIntent(PreferencesIntent.ThemeSelected(value)) }
            .padding(vertical = UIConst.paddingHairline),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppRadio(selected = current == value, onClick = { onIntent(PreferencesIntent.ThemeSelected(value)) })
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
    AppRadioRow(
        selected = current == value,
        onClick = { onIntent(PreferencesIntent.LanguageSelected(value)) },
        label = value.displayName,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PreferenceEntry(
    icon: ImageVector,
    label: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Disabled (e.g. Swagger off on this server): greyed and non-clickable.
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(vertical = UIConst.paddingSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UIConst.paddingCompact)
    ) {
        AppIcon(icon, size = AppIconSize.SMALL, tint = MaterialTheme.colorScheme.primary)
        Column {
            AppText(label, AppTextStyle.BODY)
            AppText(description, AppTextStyle.TINY)
        }
    }
}

// ── previews ────────────────────────────────────────────────────────────────
// This screen has no list, so its "empty vs populated" is WHO is looking: the
// normal user sees the account card and NOT the maintenance one, the super admin
// sees the opposite (the server 403s account self-service for the yaml admin).
// The third preview is the one people forget - the largest text step, where a
// two-line preference row is what actually overflows.

@Preview
@Composable
private fun PreferencesScreenPreview() = AppPreview(padded = false) {
    PreferencesScreen(
        theme = ThemePreference.SYSTEM,
        fontSize = FontSizePreference.MEDIUM,
        language = Language.EN,
        isAdmin = false,
        swaggerEnabled = true,
        onIntent = {},
        onNavIntent = {},
    )
}

@Preview
@Composable
private fun PreferencesScreenAdminPreview() = AppPreview(padded = false) {
    PreferencesScreen(
        theme = ThemePreference.DARK,
        fontSize = FontSizePreference.MEDIUM,
        language = Language.EN,
        isAdmin = true,
        // Preview the disabled state (server with `swagger: false`).
        swaggerEnabled = false,
        onIntent = {},
        onNavIntent = {},
    )
}

@Preview
@Composable
private fun PreferencesScreenLargeFontPreview() = AppPreview(padded = false) {
    PreferencesScreen(
        theme = ThemePreference.LIGHT,
        fontSize = FontSizePreference.XLARGE,
        language = Language.EN,
        isAdmin = false,
        swaggerEnabled = true,
        onIntent = {},
        onNavIntent = {},
    )
}
