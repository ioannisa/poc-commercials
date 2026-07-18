package eu.anifantakis.commercials.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.domain.auth.BiometricAuth
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppCard
import eu.anifantakis.commercials.core.presentation.design_system.components.AppFormColumn
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconSize
import eu.anifantakis.commercials.core.presentation.design_system.components.AppImage
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import org.koin.compose.koinInject

/**
 * The startup biometric lock: a full-screen opaque layer over EVERYTHING -
 * the remembered session opted into a biometric pass, so nothing renders or
 * reacts until the platform prompt succeeds. The prompt fires once
 * automatically; a cancelled/failed prompt leaves a retry button and the
 * password fallback (which revokes the remembered session and lands on
 * Login - a thief with the laptop cannot simply relaunch their way in).
 *
 * It wears the SAME chrome as the Login screen - full-screen background image
 * behind a centred card - so unlocking feels like the natural front door of
 * the app, not a modal that appeared out of nowhere.
 */
@Composable
internal fun BiometricGate(
    onUnlocked: () -> Unit,
    onUsePassword: () -> Unit,
) {
    val biometricAuth = koinInject<BiometricAuth>()
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        failed = false
        val ok = runCatching {
            biometricAuth.verify(StringKey.BIOMETRIC_UNLOCK_REASON.localized())
        }.getOrDefault(false)
        if (ok) onUnlocked() else failed = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Same time-of-day background as Login; decorative, so no description.
        AppImage(
            resource = AppDrawableRepo.loginBackground,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(UIConst.paddingAverage),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppFormColumn(maxWidth = 420.dp) {
                AppCard {
                    Column(
                        modifier = Modifier.padding(UIConst.paddingDouble),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIcon(
                            AppDrawableRepo.lock,
                            contentDescription = null,
                            size = AppIconSize.LARGE,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(UIConst.paddingAverage))
                        AppText(
                            "Commercials Manager",
                            AppTextStyle.SCREEN_TITLE,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        AppText(
                            Strings[StringKey.BIOMETRIC_UNLOCK_TITLE],
                            AppTextStyle.BODY,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (failed) {
                            Spacer(Modifier.height(UIConst.paddingAverage))
                            AppButton(
                                text = Strings[StringKey.BIOMETRIC_RETRY],
                                onClick = { attempt++ },
                                fillMaxWidth = true,
                            )
                            Spacer(Modifier.height(UIConst.paddingSmall))
                            AppButton(
                                text = Strings[StringKey.BIOMETRIC_USE_PASSWORD],
                                onClick = onUsePassword,
                                variant = AppButtonVariant.TEXT,
                            )
                        }
                    }
                }
            }
        }
    }
}
