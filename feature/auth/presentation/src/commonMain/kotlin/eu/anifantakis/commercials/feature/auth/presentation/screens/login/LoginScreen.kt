package eu.anifantakis.commercials.feature.auth.presentation.screens.login

import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.helper.ObserveEffects
import org.koin.compose.viewmodel.koinViewModel

/**
 * Login gate shown before any data is loaded. On success the session
 * (token, stations, roles) has been stored by the repository; [onLoggedIn]
 * then swaps the navigation stack to the main app. Also hosts the "forgot
 * password" flow (username + ONE unused recovery code sets a new password).
 */
@Composable
fun LoginScreenRoot(
    onLoggedIn: () -> Unit,
    viewModel: LoginViewModel = koinViewModel(),
) {
    ObserveEffects(viewModel.events) { effect ->
        when (effect) {
            LoginEffect.LoggedIn -> onLoggedIn()
        }
    }

    LoginScreen(
        state = viewModel.state,
        onIntent = viewModel::onAction,
    )
}

@Composable
private fun LoginScreen(
    state: LoginState,
    onIntent: (LoginIntent) -> Unit,
) {
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
                AppText(
                    "Commercials Manager",
                    AppTextStyle.SCREEN_TITLE,
                    color = MaterialTheme.colorScheme.primary
                )
                AppText(
                    if (state.recoveryMode) Strings[StringKey.LOGIN_RECOVERY_TITLE] else Strings[StringKey.LOGIN_SIGN_IN_TITLE],
                    AppTextStyle.BODY,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = state.username,
                    onValueChange = { onIntent(LoginIntent.UsernameChanged(it)) },
                    label = { Text(Strings[StringKey.LOGIN_USERNAME]) },
                    leadingIcon = { Icon(AppIcons.person, contentDescription = null) },
                    singleLine = true,
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                if (state.recoveryMode) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.recoveryCode,
                        onValueChange = { onIntent(LoginIntent.RecoveryCodeChanged(it)) },
                        label = { Text(Strings[StringKey.LOGIN_RECOVERY_CODE]) },
                        placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
                        leadingIcon = { Icon(AppIcons.key, contentDescription = null) },
                        singleLine = true,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.password,
                    onValueChange = { onIntent(LoginIntent.PasswordChanged(it)) },
                    label = { Text(Strings[if (state.recoveryMode) StringKey.LOGIN_NEW_PASSWORD else StringKey.LOGIN_PASSWORD]) },
                    leadingIcon = { Icon(AppIcons.lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { onIntent(LoginIntent.TogglePasswordVisibility) }) {
                            Icon(
                                if (state.passwordVisible) AppIcons.visibilityOff else AppIcons.visibility,
                                contentDescription = Strings[if (state.passwordVisible) StringKey.LOGIN_CD_HIDE_PASSWORD else StringKey.LOGIN_CD_SHOW_PASSWORD]
                            )
                        }
                    },
                    visualTransformation =
                        if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    singleLine = true,
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                state.errorMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    AppText(message.asString(), AppTextStyle.ERROR_NOTE)
                }
                state.infoMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    AppText(message.asString(), AppTextStyle.BODY, color = MaterialTheme.colorScheme.primary)
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = { onIntent(LoginIntent.Submit) },
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(Strings[if (state.recoveryMode) StringKey.LOGIN_RESET_PASSWORD else StringKey.LOGIN_BUTTON])
                }

                Spacer(Modifier.height(8.dp))

                TextButton(onClick = { onIntent(LoginIntent.ToggleRecoveryMode) }) {
                    AppText(
                        Strings[if (state.recoveryMode) StringKey.LOGIN_BACK_TO_LOGIN
                        else StringKey.LOGIN_FORGOT_PASSWORD],
                        AppTextStyle.NOTE,
                        color = LocalContentColor.current,
                    )
                }

                if (!state.recoveryMode) {
                    Spacer(Modifier.height(16.dp))
                    // Demo accounts - one per access layer (demo convenience)
                    AppText(
                        Strings[StringKey.LOGIN_DEMO_ACCOUNTS],
                        AppTextStyle.TABLE_HEADER,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AppText(
                        "admin / admin123 — Normal User\n" +
                            "viewer / viewer123 — Report Viewer\n" +
                            "customer / customer123 — Customer Viewer\n" +
                            "superadmin — see server.yaml",
                        AppTextStyle.TINY,
                    )
                }
            }
        }
    }
}
