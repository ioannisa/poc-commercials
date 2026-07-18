package eu.anifantakis.commercials.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.anifantakis.commercials.core.domain.auth.BiometricAuth
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconSize
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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(UIConst.paddingRegular, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(
                AppDrawableRepo.lock,
                contentDescription = null,
                size = AppIconSize.LARGE,
                tint = MaterialTheme.colorScheme.primary,
            )
            AppText(Strings[StringKey.BIOMETRIC_UNLOCK_TITLE], AppTextStyle.SECTION_TITLE)
            if (failed) {
                AppButton(
                    text = Strings[StringKey.BIOMETRIC_RETRY],
                    onClick = { attempt++ },
                )
                AppButton(
                    text = Strings[StringKey.BIOMETRIC_USE_PASSWORD],
                    onClick = onUsePassword,
                    variant = AppButtonVariant.TEXT,
                )
            }
        }
    }
}
