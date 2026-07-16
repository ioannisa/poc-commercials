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
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCard
import eu.anifantakis.commercials.core.presentation.design_system.components.AppFormColumn
import eu.anifantakis.commercials.core.presentation.design_system.components.AppOtpField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppPasswordField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextField
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import eu.anifantakis.commercials.core.presentation.helper.ObserveEffects
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import androidx.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

/**
 * Login gate shown before any data is loaded. On success the session (token,
 * stations, roles) has been stored by the repository; [onLoggedIn] then swaps
 * the navigation stack to the main app. Also hosts the two-step "forgot
 * password" flow: request an emailed 6-digit code, then enter it with a new password.
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
    val forgot = state.mode != LoginMode.LOGIN
    Column(
        modifier = Modifier.fillMaxSize().padding(UIConst.paddingAverage),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 420 is this card's own geometry (narrower than the platform form cap
        // on purpose - a login card, not a settings form).
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
                        Strings[if (forgot) StringKey.LOGIN_RECOVERY_TITLE else StringKey.LOGIN_SIGN_IN_TITLE],
                        AppTextStyle.BODY,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(UIConst.paddingAverage))

                    AppTextField(
                        value = state.username,
                        onValueChange = { onIntent(LoginIntent.UsernameChanged(it)) },
                        label = Strings[StringKey.LOGIN_USERNAME],
                        leadingIcon = AppDrawableRepo.person,
                        // Once a code is requested the username is fixed - it is who the code is for.
                        enabled = !state.isLoading && state.mode != LoginMode.FORGOT_ENTER,
                    )

                    when (state.mode) {
                        LoginMode.LOGIN -> {
                            Spacer(Modifier.height(UIConst.paddingCompact))
                            AppPasswordField(
                                value = state.password,
                                onValueChange = { onIntent(LoginIntent.PasswordChanged(it)) },
                                label = Strings[StringKey.LOGIN_PASSWORD],
                                visible = state.passwordVisible,
                                onToggleVisibility = { onIntent(LoginIntent.TogglePasswordVisibility) },
                                leadingIcon = AppDrawableRepo.lock,
                                enabled = !state.isLoading,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            )
                        }

                        LoginMode.FORGOT_REQUEST -> Unit   // username only

                        LoginMode.FORGOT_ENTER -> {
                            Spacer(Modifier.height(UIConst.paddingCompact))
                            AppText(Strings[StringKey.LOGIN_ENTER_CODE_HINT], AppTextStyle.NOTE)
                            Spacer(Modifier.height(UIConst.paddingSmall))
                            AppOtpField(
                                value = state.code,
                                onValueChange = { onIntent(LoginIntent.CodeChanged(it)) },
                                enabled = !state.isLoading,
                                isError = state.errorMessage != null,
                            )
                            Spacer(Modifier.height(UIConst.paddingCompact))
                            AppPasswordField(
                                value = state.newPassword,
                                onValueChange = { onIntent(LoginIntent.NewPasswordChanged(it)) },
                                label = Strings[StringKey.LOGIN_NEW_PASSWORD],
                                visible = state.passwordVisible,
                                onToggleVisibility = { onIntent(LoginIntent.TogglePasswordVisibility) },
                                leadingIcon = AppDrawableRepo.lock,
                                enabled = !state.isLoading,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            )
                            if (state.lockSeconds > 0) {
                                Spacer(Modifier.height(UIConst.paddingCompact))
                                AppText(
                                    Strings[StringKey.LOGIN_RESET_LOCKED].withArgs(listOf(state.lockSeconds)),
                                    AppTextStyle.ERROR_NOTE,
                                )
                            }
                        }
                    }

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
                        text = Strings[
                            when (state.mode) {
                                LoginMode.LOGIN -> StringKey.LOGIN_BUTTON
                                LoginMode.FORGOT_REQUEST -> StringKey.LOGIN_SEND_CODE
                                LoginMode.FORGOT_ENTER -> StringKey.LOGIN_RESET_PASSWORD
                            }
                        ],
                        onClick = { onIntent(LoginIntent.Submit) },
                        enabled = state.canSubmit,
                        busy = state.isLoading,
                        fillMaxWidth = true,
                    )

                    Spacer(Modifier.height(UIConst.paddingSmall))

                    AppButton(
                        text = Strings[if (forgot) StringKey.LOGIN_BACK_TO_LOGIN else StringKey.LOGIN_FORGOT_PASSWORD],
                        onClick = { onIntent(if (forgot) LoginIntent.BackToLogin else LoginIntent.StartForgot) },
                        variant = AppButtonVariant.TEXT,
                    )

                    if (state.mode == LoginMode.LOGIN) {
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

// ── previews ────────────────────────────────────────────────────────────────
// The states this card actually lives in: idle sign-in, busy submit, the error
// note, and the "enter code" step (OTP boxes + new password - a layout the idle
// preview never renders).

@Preview
@Composable
private fun LoginScreenPreview() = AppPreview(padded = false) {
    LoginScreen(state = LoginState(username = "maria.k"), onIntent = {})
}

@Preview
@Composable
private fun LoginScreenLoadingPreview() = AppPreview(padded = false) {
    LoginScreen(
        state = LoginState(username = "maria.k", password = "secret-pass", isLoading = true),
        onIntent = {},
    )
}

@Preview
@Composable
private fun LoginScreenErrorPreview() = AppPreview(padded = false) {
    LoginScreen(
        state = LoginState(
            username = "maria.k",
            password = "secret-pass",
            errorMessage = UiText.Dynamic("Wrong username or password"),
        ),
        onIntent = {},
    )
}

@Preview
@Composable
private fun LoginScreenEnterCodePreview() = AppPreview(padded = false) {
    LoginScreen(
        state = LoginState(
            username = "maria.k",
            code = "1234",
            newPassword = "new-secret-pass",
            mode = LoginMode.FORGOT_ENTER,
            passwordVisible = true,
        ),
        onIntent = {},
    )
}
