package eu.anifantakis.commercials.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.components.AppSlideOverPanel
import eu.anifantakis.commercials.feature.preferences.domain.UserPreferences
import eu.anifantakis.commercials.feature.preferences.presentation.screens.preferences.PreferencesScreenRoot
import org.koin.compose.koinInject

/**
 * Settings as a slide-over companion instead of a navigation destination.
 *
 * The screen is a HUB of shortcuts - four of its entries navigate away to
 * real screens, four open dialogs - and nothing on it is worth a place in
 * the back stack. As a panel it opens over whatever you were doing and the
 * theme/text-size choices apply live behind it, which is the whole point of
 * having them.
 *
 * Unlike [AiChatCompanionHost] this needs no per-platform form: there is no
 * conversation to keep alive, so no detach-to-OS-window - closing and
 * reopening costs nothing.
 *
 * [onNavigateAway] fires for the entries that leave: the caller dismisses
 * the panel, otherwise it would hang over the screen it just opened.
 */
@Composable
internal fun PreferencesCompanionHost(
    visible: Boolean,
    windowWidth: Dp,
    isAdmin: () -> Boolean,
    swaggerEnabled: () -> Boolean,
    aiChatEnabled: () -> Boolean,
    onClose: () -> Unit,
    onNavigateAway: () -> Unit,
    onChangePassword: () -> Unit,
    onApiTokens: () -> Unit,
    onAdminMcp: () -> Unit,
    onAppUpdate: () -> Unit,
    onManageUsers: () -> Unit,
    onMigration: () -> Unit,
    onGalaxyBridge: () -> Unit,
    onDatabases: () -> Unit,
    onOpenSwagger: () -> Unit,
    onAiChat: () -> Unit,
) {
    val prefs = koinInject<UserPreferences>()
    AppSlideOverPanel(
        visible = visible,
        windowWidth = windowWidth,
        initialWidth = prefs.panelWidthDp.dp,
        onWidthCommitted = { prefs.panelWidthDp = it.value.toInt() },
    ) { modifier, onCollapse ->
        PreferencesScreenRoot(
            isAdmin = isAdmin(),
            swaggerEnabled = swaggerEnabled(),
            aiChatEnabled = aiChatEnabled(),
            onClose = onClose,
            onChangePassword = onChangePassword,
            onApiTokens = onApiTokens,
            onAdminMcp = onAdminMcp,
            onAppUpdate = onAppUpdate,
            // The four that leave: dismiss on the way out.
            onManageUsers = { onNavigateAway(); onManageUsers() },
            onMigration = { onNavigateAway(); onMigration() },
            onGalaxyBridge = { onNavigateAway(); onGalaxyBridge() },
            onDatabases = { onNavigateAway(); onDatabases() },
            onOpenSwagger = onOpenSwagger,
            onAiChat = onAiChat,
            modifier = modifier,
            onCollapse = onCollapse,
        )
    }
}
