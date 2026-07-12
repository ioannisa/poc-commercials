package eu.anifantakis.commercials.feature.auth.presentation.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCard
import eu.anifantakis.commercials.core.presentation.design_system.components.AppFormColumn
import eu.anifantakis.commercials.core.presentation.design_system.components.AppPasswordField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.helper.ObserveEffects
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
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
        modifier = Modifier.fillMaxSize().padding(UIConst.paddingAverage),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 420 is this card's own geometry (narrower than the platform form
        // cap on purpose - a login card, not a settings form).
        AppFormColumn(maxWidth = 420.dp) {
            AppCard {
                Column(
                    modifier = Modifier.padding(UIConst.paddingDouble),
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

                    Spacer(Modifier.height(UIConst.paddingAverage))

                    AppTextField(
                        value = state.username,
                        onValueChange = { onIntent(LoginIntent.UsernameChanged(it)) },
                        label = Strings[StringKey.LOGIN_USERNAME],
                        leadingIcon = AppIcons.person,
                        enabled = !state.isLoading,
                    )

                    if (state.recoveryMode) {
                        Spacer(Modifier.height(UIConst.paddingCompact))
                        AppTextField(
                            value = state.recoveryCode,
                            onValueChange = { onIntent(LoginIntent.RecoveryCodeChanged(it)) },
                            label = Strings[StringKey.LOGIN_RECOVERY_CODE],
                            placeholder = "XXXX-XXXX-XXXX-XXXX",
                            leadingIcon = AppIcons.key,
                            enabled = !state.isLoading,
                        )
                    }

                    Spacer(Modifier.height(UIConst.paddingCompact))

                    AppPasswordField(
                        value = state.password,
                        onValueChange = { onIntent(LoginIntent.PasswordChanged(it)) },
                        label = Strings[if (state.recoveryMode) StringKey.LOGIN_NEW_PASSWORD else StringKey.LOGIN_PASSWORD],
                        visible = state.passwordVisible,
                        onToggleVisibility = { onIntent(LoginIntent.TogglePasswordVisibility) },
                        leadingIcon = AppIcons.lock,
                        enabled = !state.isLoading,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )

                    state.errorMessage?.let { message ->
                        Spacer(Modifier.height(UIConst.paddingCompact))
                        AppText(message.asString(), AppTextStyle.ERROR_NOTE)
                    }
                    state.infoMessage?.let { message ->
                        Spacer(Modifier.height(UIConst.paddingCompact))
                        AppText(message.asString(), AppTextStyle.BODY, color = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(Modifier.height(UIConst.paddingAverage))

                    AppButton(
                        text = Strings[if (state.recoveryMode) StringKey.LOGIN_RESET_PASSWORD else StringKey.LOGIN_BUTTON],
                        onClick = { onIntent(LoginIntent.Submit) },
                        enabled = state.canSubmit,
                        busy = state.isLoading,
                        fillMaxWidth = true,
                    )

                    Spacer(Modifier.height(UIConst.paddingSmall))

                    AppButton(
                        text = Strings[if (state.recoveryMode) StringKey.LOGIN_BACK_TO_LOGIN
                        else StringKey.LOGIN_FORGOT_PASSWORD],
                        onClick = { onIntent(LoginIntent.ToggleRecoveryMode) },
                        variant = AppButtonVariant.TEXT,
                    )

                    if (!state.recoveryMode) {
                        Spacer(Modifier.height(UIConst.paddingRegular))
                        // Demo accounts - one per access layer (demo convenience)
                        AppText(
                            Strings[StringKey.LOGIN_DEMO_ACCOUNTS],
                            AppTextStyle.TABLE_HEADER,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppText(
                            Strings[StringKey.LOGIN_DEMO_ACCOUNTS_BODY].withArgs(
                                listOf(
                                    Strings[StringKey.ROLE_NORMAL_USER],
                                    Strings[StringKey.ROLE_REPORT_VIEWER],
                                    Strings[StringKey.ROLE_CUSTOMER_VIEWER],
                                )
                            ),
                            AppTextStyle.TINY,
                        )
                    }
                }
            }
        }
    }
}
