package eu.anifantakis.commercials.feature.user_management.presentation.screens.user_management

import eu.anifantakis.commercials.core.presentation.string_resources.localized
import eu.anifantakis.commercials.core.presentation.util.toStringKey
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCard
import eu.anifantakis.commercials.core.presentation.design_system.components.AppDialog
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.design_system.components.rememberClipboardCopy
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.feature.user_management.domain.ManagedUser
import eu.anifantakis.commercials.feature.user_management.domain.UserGrant
import kotlinx.collections.immutable.persistentListOf
import androidx.compose.ui.tooling.preview.Preview
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
        onNavIntent = { navIntent ->
            when (navIntent) {
                UserManagementScreenNavIntent.OnBack -> onBack()
            }
        },
    )
}

/**
 * Navigation-only actions of this screen — ALWAYS routed through this single
 * parameter (a predictable shape you can expect on every screen, ready to
 * accept more nav without a refactor). Not a ViewModel [UserManagementIntent].
 */
private sealed interface UserManagementScreenNavIntent {
    data object OnBack : UserManagementScreenNavIntent
}

@Composable
private fun UserManagementScreen(
    state: UserManagementState,
    stationChoices: List<Pair<String, String>>,
    onIntent: (UserManagementIntent) -> Unit,
    onNavIntent: (UserManagementScreenNavIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(UIConst.paddingRegular)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(
                label = Strings[StringKey.COMMON_BACK],
                icon = AppIcons.arrowBack,
                onClick = { onNavIntent(UserManagementScreenNavIntent.OnBack) },
            )
            AppText(Strings[StringKey.USER_MGMT_TITLE], AppTextStyle.SCREEN_TITLE)
            Spacer(Modifier.weight(1f))
            AppButton(
                text = Strings[StringKey.USER_MGMT_NEW_USER],
                onClick = { onIntent(UserManagementIntent.ShowCreate) },
                leadingIcon = AppIcons.add,
            )
        }

        state.message?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }

        Spacer(Modifier.height(UIConst.paddingSmall))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
            items(state.users, key = { it.id }) { user ->
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(UIConst.paddingCompact),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppText(user.username, AppTextStyle.ITEM_TITLE)
                                Spacer(Modifier.width(UIConst.paddingSmall))
                                AppText(user.displayName, AppTextStyle.BODY, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (user.isAdmin) {
                                    Spacer(Modifier.width(UIConst.paddingSmall))
                                    AppText(
                                        Strings[StringKey.USER_MGMT_SUPER_ADMIN_BADGE],
                                        AppTextStyle.TINY,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (!user.isAdmin) {
                                val summary = user.grants.joinToString("  ·  ") {
                                    val cc = it.clientCode?.let { c -> " ($c)" } ?: ""
                                    "${it.stationId}: ${AppRole.parse(it.role).toStringKey().localized()}$cc"
                                }.ifEmpty { Strings[StringKey.USER_MGMT_NO_ACCESS] }
                                AppText(summary, AppTextStyle.NOTE)
                            }
                        }
                        if (!user.isAdmin) {
                            AppIconButton(
                                label = Strings[StringKey.USER_MGMT_CD_RESET_PASSWORD],
                                icon = AppIcons.lockReset,
                                onClick = { onIntent(UserManagementIntent.ResetRequested(user)) },
                            )
                            AppIconButton(
                                label = Strings[StringKey.USER_MGMT_CD_EDIT_GRANTS],
                                icon = AppIcons.edit,
                                onClick = { onIntent(UserManagementIntent.EditGrantsRequested(user)) },
                            )
                            AppIconButton(
                                label = Strings[StringKey.USER_MGMT_CD_DELETE_USER],
                                icon = AppIcons.delete,
                                onClick = { onIntent(UserManagementIntent.DeleteRequested(user)) },
                                tint = MaterialTheme.colorScheme.error,
                            )
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
    state.tempPassword?.let { result ->
        TempPasswordResultDialog(result = result, onIntent = onIntent)
    }
    state.editGrants?.let { dialog ->
        EditGrantsDialog(dialog = dialog, stationChoices = stationChoices, onIntent = onIntent)
    }
    state.delete?.let { user ->
        AppDialog(
            title = Strings[StringKey.USER_MGMT_DELETE_TITLE].withArgs(listOf(user.username)),
            onDismiss = { onIntent(UserManagementIntent.DismissDelete) },
            confirmText = Strings[StringKey.COMMON_DELETE],
            onConfirm = { onIntent(UserManagementIntent.ConfirmDelete) },
            dismissText = Strings[StringKey.COMMON_CANCEL],
            destructive = true,
        ) {
            AppText(Strings[StringKey.USER_MGMT_DELETE_BODY], AppTextStyle.BODY)
        }
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
            modifier = Modifier.fillMaxWidth().padding(vertical = UIConst.paddingHairline),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppText(stationName, AppTextStyle.BODY, modifier = Modifier.weight(1f))
            AppButton(
                text = if (role == NO_ACCESS) Strings[StringKey.USER_MGMT_NO_ACCESS]
                else Strings[AppRole.parse(role).toStringKey()],
                onClick = { expanded = true },
                variant = AppButtonVariant.SECONDARY,
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                (listOf(NO_ACCESS) + AppRole.entries.map { it.name }).forEach { option ->
                    DropdownMenuItem(
                        text = { AppText(if (option == NO_ACCESS) Strings[StringKey.USER_MGMT_NO_ACCESS] else Strings[AppRole.parse(option).toStringKey()], AppTextStyle.BUTTON) },
                        onClick = {
                            onRoleChanged(stationId, option)
                            expanded = false
                        }
                    )
                }
            }
        }
        if (role == AppRole.CUSTOMER_VIEWER.name) {
            AppTextField(
                value = grants.clientCodes[stationId] ?: "",
                onValueChange = { onCodeChanged(stationId, it) },
                label = Strings[StringKey.USER_MGMT_CLIENT_CODE_ON].withArgs(listOf(stationName)),
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
    AppDialog(
        title = Strings[StringKey.USER_MGMT_NEW_USER_TITLE],
        onDismiss = { onIntent(UserManagementIntent.DismissCreate) },
        confirmText = Strings[StringKey.USER_MGMT_CREATE],
        onConfirm = { onIntent(UserManagementIntent.ConfirmCreate) },
        dismissText = Strings[StringKey.COMMON_CANCEL],
        confirmEnabled = dialog.canSubmit,
        confirmBusy = dialog.busy,
    ) {
        AppTextField(
            value = dialog.username,
            onValueChange = { onIntent(UserManagementIntent.CreateUsernameChanged(it)) },
            label = Strings[StringKey.LOGIN_USERNAME],
            enabled = !dialog.busy,
        )
        AppTextField(
            value = dialog.displayName,
            onValueChange = { onIntent(UserManagementIntent.CreateDisplayNameChanged(it)) },
            label = Strings[StringKey.USER_MGMT_DISPLAY_NAME],
            enabled = !dialog.busy,
        )
        // Optional: where the auto-generated temp password gets mailed. No email
        // -> the admin relays the temp password from the result dialog instead.
        AppTextField(
            value = dialog.email,
            onValueChange = { onIntent(UserManagementIntent.CreateEmailChanged(it)) },
            label = Strings[StringKey.USER_MGMT_EMAIL],
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = !dialog.busy,
        )
        AppText(Strings[StringKey.USER_MGMT_STATION_ACCESS], AppTextStyle.BODY_STRONG)
        GrantsEditor(
            stationChoices = stationChoices,
            grants = dialog.grants,
            onRoleChanged = { st, role -> onIntent(UserManagementIntent.CreateGrantRoleChanged(st, role)) },
            onCodeChanged = { st, code -> onIntent(UserManagementIntent.CreateClientCodeChanged(st, code)) },
        )
        dialog.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }
    }
}

@Composable
private fun ResetPasswordDialog(
    dialog: ResetPasswordDialogState,
    onIntent: (UserManagementIntent) -> Unit,
) {
    AppDialog(
        title = Strings[StringKey.USER_MGMT_RESET_TITLE].withArgs(listOf(dialog.user.username)),
        onDismiss = { onIntent(UserManagementIntent.DismissReset) },
        confirmText = Strings[StringKey.USER_MGMT_RESET],
        onConfirm = { onIntent(UserManagementIntent.ConfirmReset) },
        dismissText = Strings[StringKey.COMMON_CANCEL],
        confirmEnabled = dialog.canSubmit,
        confirmBusy = dialog.busy,
    ) {
        AppText(Strings[StringKey.USER_MGMT_RESET_INFO], AppTextStyle.BODY)
        dialog.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }
    }
}

/**
 * The one-time temp password after a create/reset: shown ONCE, copyable, so the
 * admin can relay it even if the email didn't go out (or no email was on file).
 */
@Composable
private fun TempPasswordResultDialog(
    result: TempPasswordResultState,
    onIntent: (UserManagementIntent) -> Unit,
) {
    val copy = rememberClipboardCopy()
    AppDialog(
        title = Strings[StringKey.USER_MGMT_TEMP_TITLE],
        onDismiss = { onIntent(UserManagementIntent.DismissTempPassword) },
        confirmText = Strings[StringKey.COMMON_CLOSE],
        onConfirm = { onIntent(UserManagementIntent.DismissTempPassword) },
    ) {
        AppText(Strings[StringKey.USER_MGMT_TEMP_INFO].withArgs(listOf(result.username)), AppTextStyle.BODY)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = UIConst.paddingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(result.tempPassword, AppTextStyle.MONO, modifier = Modifier.weight(1f))
            AppButton(
                text = Strings[StringKey.COMMON_COPY],
                onClick = { copy(result.tempPassword) },
                variant = AppButtonVariant.SECONDARY,
            )
        }
        AppText(
            Strings[if (result.emailSent) StringKey.USER_MGMT_TEMP_EMAILED else StringKey.USER_MGMT_TEMP_NOT_EMAILED],
            AppTextStyle.NOTE,
        )
    }
}

@Composable
private fun EditGrantsDialog(
    dialog: EditGrantsDialogState,
    stationChoices: List<Pair<String, String>>,
    onIntent: (UserManagementIntent) -> Unit,
) {
    AppDialog(
        title = Strings[StringKey.USER_MGMT_GRANTS_TITLE].withArgs(listOf(dialog.user.username)),
        onDismiss = { onIntent(UserManagementIntent.DismissGrants) },
        confirmText = Strings[StringKey.COMMON_SAVE],
        onConfirm = { onIntent(UserManagementIntent.ConfirmGrants) },
        dismissText = Strings[StringKey.COMMON_CANCEL],
        confirmBusy = dialog.busy,
    ) {
        GrantsEditor(
            stationChoices = stationChoices,
            grants = dialog.grants,
            onRoleChanged = { st, role -> onIntent(UserManagementIntent.GrantRoleChanged(st, role)) },
            onCodeChanged = { st, code -> onIntent(UserManagementIntent.GrantClientCodeChanged(st, code)) },
        )
        dialog.error?.let {
            AppText(it.asString(), AppTextStyle.ERROR_NOTE)
        }
    }
}

// ── previews ────────────────────────────────────────────────────────────────

private val previewStationChoices = listOf(
    "crete-tv" to "Crete TV",
    "radio-984" to "Radio 984",
    "aegean-fm" to "Aegean FM",
)

/**
 * One row of each kind the list can hold: the super admin (badge, NO action
 * buttons), a multi-station operator, a customer viewer scoped to a client code,
 * and an account with no grant at all - the row that renders "no access".
 */
private val previewUsers = persistentListOf(
    ManagedUser(
        id = 1,
        username = "admin",
        displayName = "Super Administrator",
        isAdmin = true,
    ),
    ManagedUser(
        id = 2,
        username = "maria.k",
        displayName = "Maria Kalogeraki",
        grants = listOf(
            UserGrant("crete-tv", AppRole.NORMAL_USER.name),
            UserGrant("radio-984", AppRole.REPORT_VIEWER.name),
        ),
    ),
    ManagedUser(
        id = 3,
        username = "nikos.p",
        displayName = "Nikos Papadakis",
        grants = listOf(
            UserGrant("aegean-fm", AppRole.CUSTOMER_VIEWER.name, clientCode = "ACME"),
        ),
    ),
    ManagedUser(
        id = 4,
        username = "temp.account",
        displayName = "Temporary Account",
    ),
)

@Preview
@Composable
private fun UserManagementScreenPreview() = AppPreview(padded = false) {
    UserManagementScreen(
        state = UserManagementState(users = previewUsers),
        stationChoices = previewStationChoices,
        onIntent = {},
        onNavIntent = {},
    )
}

/** Fresh install: the stations exist, the accounts don't yet. */
@Preview
@Composable
private fun UserManagementScreenEmptyPreview() = AppPreview(padded = false) {
    UserManagementScreen(
        state = UserManagementState(),
        stationChoices = previewStationChoices,
        onIntent = {},
        onNavIntent = {},
    )
}
